# Core Engine Changes — Upstream Update Tracking

This repo tracks two very different kinds of changes: content that lives entirely under
`forge-gui/res/adventure/The Forsaken Realms/` (safe — upstream Forge updates never touch that
folder, so nothing there can ever conflict), and edits to **existing, shared Forge engine files**
(risky — an upstream update could change the exact same file, method, or line).

This document exists for that second kind only: a maintained list of every stock engine file this
mod has modified, so that when Card-Forge/forge ships an update (a few times a week), it's fast to
check "did upstream touch anything I changed here?" instead of re-reading every diff from scratch.

## How to use this when pulling an upstream update

Don't rely on memory or this doc alone for the actual mechanics — git already has the ground
truth. This doc is the *index* into it (which files to even look at), git is the *proof* (what
actually changed, on both sides).

```bash
git fetch upstream
# See what upstream changed, scoped to just the files this doc lists below:
git log --oneline <old-upstream-ref>..upstream/master -- <file>
git diff <old-upstream-ref>..upstream/master -- <file>
```

If upstream's diff for a file overlaps the method/section this doc says we touched, that's a real
conflict to resolve by hand (re-apply our change on top of upstream's new version). If upstream
never touched the file, the merge/rebase should go through untouched. Standard `git merge upstream/
master` (or `git rebase`) will still flag textual conflicts either way — this doc just tells you
*where to expect them* and *why our side looks the way it does*, so reconciling isn't a cold read.

**Keeping this doc current is part of the workflow, not optional** (see `CLAUDE.md`'s ground
rules): any session that edits a file **outside** `forge-gui/res/adventure/The Forsaken Realms/`
or a wholly new file must add/update an entry here in the same round, the same way `MOD_CHANGELOG.
md` already gets updated after every change.

## Modified files (existing engine code, edited)

Grouped by subsystem. Each entry: what changed, why (one line — full reasoning is in
`MOD_CHANGELOG.md`, search for the linked feature).

### World generation & the overworld map
- **`forge-gui-mobile/src/forge/adventure/world/World.java`** — the single most-touched file.
  Added: `repaintBiomeAroundTown()` (live terrain recolor, #7), fog-of-war state/rendering
  (`explored`/`fogOfWarPixmap`/`isCurrentlyVisible`, #3), day/night clock (`dayProgress`/
  `dayCount`/`advanceTime()`, #6), per-color attack countdown (`colorNextAttackDay`, #7). **Also a
  bug fix, not a feature**: the async structure-generation task now always marks itself done even
  on failure, so a crash there can't hang world-gen forever (previously could, regardless of cause
  - see the "world-gen hang" entry in `MOD_CHANGELOG.md`). Territory Control's current approach
  (#7, generate normally then sweep - see `MOD_CHANGELOG.md`'s "world-gen approach redesigned")
  added `neutralizeTerritoryOutsideRadius()` (inverse of `repaintBiomeAroundTown()`),
  `isTerritoryControlEnabled()`, and `redrawAllPoiMarkers()` (fixes the sweep clipping nearby POI
  minimap icons), plus one call near the end of `generateNew()` into
  `TerritoryControl.neutralizeAfterGeneration()`. Territory Expansion (#7, same feature, later same
  day) added `claimWastelandRing()` (daily incremental version of `neutralizeTerritoryOutsideRadius
  ()` - claims an annulus of currently-wasteland tiles for a color instead of a one-time full
  sweep), gave `regenerateDoodadsInRadius()` an `innerRadiusTiles` parameter so a ring-only claim
  doesn't re-randomize the whole already-claimed interior, and added persisted per-color state
  `colorTerritoryRadius` (same save/load pattern as `colorNextAttackDay`). Terrain Switch-Out (#7,
  same feature, later same day - see `MOD_CHANGELOG.md`) added the structure-translation machinery
  (`translateStructure()`, `buildStructureSwapTable()`/`getStructureSwapTable()`, `pickReplacement()`,
  `candidatesByName()`/`candidatesForCategory()`, `STRUCTURE_CATEGORY`, `structureSwapCache`) that
  all 3 repaint methods now call instead of zeroing `terrainMap` outright - reskins a repainted
  tile's existing mountain/rock/tree/water structure to the new biome's closest equivalent instead
  of deleting it. Territory Control playtest round 7 (#7, same day) gave `claimWastelandRing()` a
  `List<Vector2> otherAnchors` parameter and switched its claim condition from "am I within my own
  radius" to a Voronoi-style "is my anchor the nearest of all colors' castles and the player's
  Spawn" check - Spawn is now baked into the method as a permanent rival anchor, replacing the
  removed `SPAWN_PROTECTION_RADIUS_TILES` constant/hard-block entirely. Also changed all 3 repaint
  methods to carry a road tile's existing road bit forward into a repaint instead of skipping the
  tile outright (fixes a border that traced roads specifically). Territory Control's placement (#7,
  redesigned 2026-08-06, current approach - see `MOD_CHANGELOG.md` for the two earlier approaches
  this replaced, both fully removed, not left behind as dead code) splits `generateNew()`'s per-tile
  placement loop into Pass A (claim only - `biomeMap` bit-setting, unchanged from the original
  single-pass logic) and Pass B (terrain/structure computation, moved to run after POI placement so
  every AI color's real castle position is already known, no prediction involved). Pass B computes
  each AI color's real content within `TerritoryControl.CASTLE_KEEP_RADIUS_TILES` of its real castle
  and colorless's own content everywhere else in that color's claim, via a per-color clone of
  colorless's `structures[]` built at that color's own scale (`cloneStructures()`, reused from the
  prior approach - still needed, still avoids a `structureDataMap` identity collision if colorless's
  own objects were shared directly). `neutralizeTerritoryOutsideRadius()` lost its reskinning half
  (the `translateStructure()` call and `terrainMap` rewrite) - it now only flips `biomeMap`'s
  ownership bit and repaints the minimap/fog-of-war outside the radius, since Pass B already
  computed correct content the first time. `TerritoryControl.findCastle()`/`.COLORS`/
  `.CASTLE_KEEP_RADIUS_TILES` made `public` so `World.java` calls/reads them directly instead of
  duplicating the same lookup/constants (a duplicated radius specifically would risk Pass B and the
  post-generation ownership pass disagreeing about the boundary - a real rendering bug, not just a
  style mismatch, since rendering interprets `terrainMap`'s index using whichever biome `biomeMap`'s
  bit currently names). Two follow-up fixes same day: `rebakeMinimapAfterTerritoryControl()` (full
  minimap re-derive from final `biomeMap`/`terrainMap` state, called once after the neutralize
  sweep) and a `redrawAllPoiMarkers()` call added to the end of `repaintBiomeAroundTown()` (a live
  town-capture repaint used to leave that town's own minimap marker painted over - the one-time
  world-gen sweep already called this, live captures never had). Extended Pass B's approach to daily
  territory expansion (#7, same day - `claimWastelandRing()` still used the old
  `translateStructure()` reskin, the same density ceiling Pass B was built to eliminate for the
  initial circle, just never extended past it): added three lazily-built, persistent caches
  (`nativeStructurePatternCache`/`getOrBuildNativePattern()`, `territoryNoise`/`getTerritoryNoise()`,
  `colorlessRedirectStructureCache`/`getOrBuildColorlessRedirectStructures()`) standing in for
  `generateNew()`'s own `structureDataMap`/`noise`/redirect-structures map, all three of which are
  local variables unreachable from a method called repeatedly during actual gameplay (and never
  populated at all for a game loaded from a save, since loading skips `generateNew()` entirely).
  Pass B's own inline redirect-structures precompute was replaced with a call to the new shared
  helper, so the initial circle and later expansion read from the same cache instead of two
  independently-built copies that could drift apart. **Same-round regression fix**: that redirect-
  structures build moved from a plain synchronous call to a background `CompletableFuture.runAsync()`
  one (`getColorlessRedirectStructuresIfReady()`, non-blocking - returns `null` if not built yet
  instead of waiting), after the synchronous version caused a real freeze calling it from
  `claimWastelandRing()` mid-gameplay (see `MOD_CHANGELOG.md`). `buildColorlessRedirectStructuresBlocking()`
  keeps the old synchronous behavior for Pass B, where it's still safe. `nativeStructurePatternCache`/
  `colorlessRedirectStructureCache` changed from `HashMap` to `ConcurrentHashMap` accordingly (now
  touched by concurrent background builds, one potential per color). New public
  `World.prewarmTerritoryControlCaches()`, called once from `WorldSave.load()` right after a save
  finishes loading, to give those background builds a head start before gameplay could trigger them.
  **Same-round bug fix, pre-existing, not caused by this week's work**: `regenerateDoodadsInRadius()`
  gained a `Set<Long> claimedTiles` overload (plus a small `packTile(x,y)` helper) - it used to
  re-derive "which tiles does this color own" using only the geometric annulus, silently ignoring
  the nearest-anchor (Voronoi) check `claimWastelandRing()`'s own ground-ownership loop applies, so a
  tile geometrically in range but actually closer to a rival got that color's doodads placed on it
  (and had a rival's legitimate doodads incorrectly removed) even though ground ownership correctly
  stayed with the rival. `claimWastelandRing()` now collects its own loop's actual claimed-tile set
  and passes it straight through, so there's one definition of ownership, not two that can disagree.
  `repaintBiomeAroundTown()` (the only other caller) is unaffected - a 4-argument overload preserves
  its old geometric-only behavior by forwarding `null`. **Same-round bug fix**: `generateNew()`
  never reset `dayCount`/`dayProgress`/`colorNextAttackDay`/`colorTerritoryRadius` - only `load()`
  did. Since `WorldSave.currentSave` (and its `World`) is a singleton constructed once per app run,
  starting a new game without restarting the app reused the same object with all four still carrying
  over from the previous session (confirmed: a fresh game started on day 31, matching where the
  prior save had left off). Now explicitly reset alongside the existing cache-clearing block at the
  top of `generateNew()`. **Same-round follow-up (next round)**: `claimWastelandRing()`'s single
  `otherAnchors` rival list split into two - `otherAnchors` (other AI castles + Spawn, unchanged,
  still unbounded) and a new `boundedRivalAnchors` parameter (player-owned captured towns, each
  capped to `TerritoryControl.CASTLE_KEEP_RADIUS_TILES` instead of an unbounded Voronoi cell, per
  user decision after an unbounded town's cell was confirmed to grow into a large, fully-enclosed
  hole once a color's own circle passed it on every side). Internally, each rival tile now carries
  `{x, y, capRadiusSq}` (`-1` = unbounded). **Caught and fixed immediately (same round)**: that cap
  used `CASTLE_KEEP_RADIUS_TILES` (20) at first, which didn't match the radius
  `repaintBiomeAroundTown()` actually repaints on capture (`TerritoryControl.RECOLOR_RADIUS`, 10) -
  changed to derive from `RECOLOR_RADIUS` instead, so protection never exceeds what's visibly
  recolored. Minimap-detail + blue-border round (2026-08-08, from the other machine - entry added
  retroactively by the cross-machine review, the round itself missed the ledger): new private
  `redrawMinimapTile(x, rawY[, decodeBiome])` (per-tile version of the full minimap bake, sits right
  next to the stock bake code - upstream-collision-prone) replacing the flat index-0 stamp in all 3
  live repaint paths; `claimWastelandRing()`'s ownership write changed from single-bit to
  `existingRoadBit | colorlessBit | colorBit` (keeping waste underneath the color is the actual
  blue-border fix - restores the neighbor-bit symmetry `generateBiomeSprite()`'s base-layer
  promotion logic needs, so ocean stops getting promoted at claim edges); `claimWastelandRing()`
  also now calls `redrawAllPoiMarkers()` after a claiming ring. The review also fixed a decode bug
  in that round's own fix: an expansion-claimed tile's `terrainMap` is written in colorless index
  space, so `redrawMinimapTile()` gained the optional `decodeBiome` parameter to resolve the
  structure portion against colorless's tables instead of the claiming color's differently-sized
  ones (which drew wrong-or-no structure pixels - the flat-minimap symptom persisting). Blocky-creep
  fix (same day): both `claimWastelandRing()` and `repaintBiomeAroundTown()` now fire their
  `onTileRepainted` chunk-patch callbacks AFTER their loops complete (claimed tiles + 1-tile border,
  deduped) instead of per tile mid-loop - a mid-loop patch blended each tile against
  not-yet-processed neighbors and never revisited it, leaving claims a grid of stale hard-edged
  squares until the player walked over them. Five-request round (same day): new persisted
  `townTerritoryRadius` map (per-captured-town territory radius, save/load/generateNew-reset like
  `colorTerritoryRadius`); `claimWastelandRing()`'s `boundedRivalAnchors` retyped to
  `List<Pair<Vector2,Integer>>` (per-anchor protection cap = that town's current radius); new
  fog-of-war Revealed-areas support - `playerTownVisionAreas` cache + public
  `rebuildPlayerTownVision()`, and `isCurrentlyVisible()` now also returns true inside any
  player-owned town's current territory radius (new `TownRestoration` import). Same-round
  verification fixes: `claimWastelandRing()` returns its claimed-tile count (callers revert a
  town's radius on zero-ground rings), skips the Spawn rival for the player's own claims, and
  accepts "player" as a claiming color (redirect pattern built at COLORLESS's extent - see
  `isClaimingColor()`). Blue-border completion: `repaintBiomeAroundTown()` keeps the waste bit
  underneath a "player"-over-former-waste repaint (dual-bit, same mechanism as
  `claimWastelandRing()`'s fix - player's tables are exact colorless clones so the kept layer
  decodes coherently; AI captures deliberately stay single-bit). Resource spawns (new feature):
  persisted `resourceSpawns` list + `resourceSpawnsSeeded` flag, save/load/generateNew-reset like
  the Territory Control maps, logic in the new `ResourceSpawns` util class. Dungeon rotation (#15):
  three more persisted maps (`poiDespawnDay`/`poiRespawnDay`/`poiFailedAttempts`, keyed by POI id),
  `redrawAllPoiMarkers()` now skips inactive POIs, and a new public `refreshWorldMapMarkers()`
  (ground rebake + marker redraw) so a runtime POI hide/show can repaint its baked minimap icon
  away. Playtest-fix round: `updateFogOfWarPixmap()` paints solid black for UNEXPLORED tiles
  (it unconditionally painted the hazed "discovered" look, which leaked AI territory onto a fresh
  fog-of-war minimap via Territory Control's per-tile calls); the POI placement loop multiplies
  rotatable dungeon/cave counts by `DungeonRotation.POOL_MULTIPLIER` (pool rotation, new worlds
  only) and calls `DungeonRotation.initializeNewWorld()` right after placement; new persisted
  `poiActiveTarget` int and `questAcceptedDay` map (side-quest timers, kept off AdventureQuestData
  for serialization compat). Capture-roads round (2026-08-09): new public
  `buildRoad(List<PointOfInterest> waypoints, onTileRepainted)` - runtime version of
  `generateNew()`'s town-road pass (same Bresenham/`roadBit`/`terrainMap=0` treatment AND its
  `[x][height - y]` raw index convention, so runtime roads line up with the generated network),
  skips already-road tiles, updates minimap + fog pixmap per changed tile, chunk-patches changed
  tiles + a 2-tile blend ring; called from `TerritoryControl.connectCapturedTownByRoad()`.
  FoW discovery-flash round (2026-08-10): new time-limited "temporarily revealed" tier on top of
  the existing 3 (`temporaryRevealTimers`/`temporarilyReveal()`/`isTemporarilyRevealed()`/
  `tickTemporaryReveals()`), checked from `isCurrentlyVisible()` alongside the live vision circle
  and the persistent tier - a newly-discovered tile flashes fully bright for a few seconds before
  settling into its ordinary tier, instead of jumping straight there (#3).
  Progressive Set Unlocks round (2026-08-12, #4): new persisted `colorEditionShards`
  (`Map<String, List<String>>`, same save/load/NG+-reset pattern as `colorTerritoryRadius`) and
  `isEditionProgressionEnabled()`. `EditionProgression.seedColorShards(this)` called once near the
  end of `generateNew()` (after the Territory Control sweep, gated behind the new flag).
- **`forge-gui-mobile/src/forge/adventure/pointofintrest/PointOfInterest.java`** — Dungeon
  rotation (#15): `getActive()` now honors the persisted `active` field (previously write-only -
  saved/loaded but never consulted; no data entry ships `active:false`, verified, so stock behavior
  is unchanged), plus a `setActive(boolean)` setter. This is the whole despawn/respawn mechanism -
  sprite draw, entry collision, and quest target selection all already consult `getActive()`.
- **`forge-gui-mobile/src/forge/adventure/world/BiomeStructure.java`** — **bug fix**: guards
  against a wave-function-collapse chunk smaller than the pattern size (`N`), which used to throw
  `ArrayIndexOutOfBoundsException`; also fixed a pre-existing typo (`my < targetWidth` should've
  been `targetHeight`, harmless until now but wrong regardless).
- **`forge-gui-mobile/src/forge/adventure/world/WorldSave.java`** — added
  `getAllPointOfInterestChanges()`, a small accessor so a global per-day sweep (Economy Buildings,
  #10) can iterate every town's state without knowing ids in advance. Also added one call,
  `currentSave.world.prewarmTerritoryControlCaches()`, right after a successful `world.load()` inside
  `WorldSave.load()` (#7, freeze-regression fix - see `World.java`'s own entry above). **Same-round
  bug fix (next round)**: `WorldStage`/`WorldBackground` are long-lived singletons for the whole app
  session, and a chunk's decoration Actor list is only ever built once and cached (see
  `WorldBackground.reloadChunkObjects()`'s own pre-existing comment) - a plain load never invalidated
  any of that, so loading an earlier save mid-session while standing at the same spot could still show
  doodads left over from a later, abandoned session (confirmed, reported bug). Fixed by looping over
  every chunk coordinate right after a load succeeds and calling the existing (already safe/no-op for
  an unloaded chunk) `WorldStage.reloadBackgroundChunkObjects(cx, cy)` for each. Also added
  `peekPointOfInterestChanges(String id)` (2026-08-08, from the other machine - entry added
  retroactively by the cross-machine review): a read-only lookup returning null when a POI has no
  recorded changes, unlike the get-or-create accessor above - Territory Control's daily sweep and the
  World Standings town count query every POI on the map, and pure reads materializing an empty
  `PointOfInterestChanges` per scanned POI was permanently growing the save file. Five-request round:
  `load()` now calls `world.rebuildPlayerTownVision()` right after `pointOfInterestChanges` loads -
  the fog-of-war Revealed-areas cache reads town-ownership flags from it, so `World.load()` alone
  runs too early (see its own comment). Six-report round (2026-08-08 evening, duplicate-town-names
  root cause): the "Can not place POI ...Rerunning" placement-restart block now calls
  `resetTownNamePool()` on every biome - each discarded pass consumed town names it never kept, so
  enough reruns (frequent since pool rotation's 5x density) drained the pool dry and every later
  town fell back to its template's generic name. `load()` also calls
  `TownRestoration.migrateGenericTownNames(this)` to repair saves generated while the bug was
  live (inert on stock planes via the `townReconstructionEnabled` gate). Pentagon-stall round
  (2026-08-08 late): `claimWastelandRing()`'s Spawn rival anchor is now BOUNDED
  (`TerritoryControl.SPAWN_PROTECTION_RADIUS_TILES` cap) - unbounded, Spawn at map center
  (playerStartPos 0.5/0.5) out-Voronoi'd every castle for the entire central wasteland, so AI
  expansion visibly stalled along the castle-vs-Spawn bisector polygon (the user's "perfect
  upside down pentagon"). POI placement: each biome's template list is placement-priority sorted
  (essentials -> towns -> bulk, `poiPlacementPriority()`/`isEssentialPoi()`), and the attempt
  loop no longer SILENTLY drops a POI whose 500 attempts all failed the out-of-bounds/wrong-biome
  check (that path never reached the counter==499 rerun; a world shipped missing White's Capital
  and the Emrakul castle) - an essential drop reruns placement (10-rerun budget guards against an
  impossible-layout hang), a non-essential drop at least logs. New public
  `addPointOfInterestNear()` (places + registers a brand-new POI at a free tile near a position,
  with minimap repaint) for `TerritoryControl.ensureCapital()`'s no-town-to-promote fallback.
  Night round (2026-08-08): `claimWastelandRing()` redesigned to the weighted-pull contested
  model - signature now takes `Map<String, List<float[]>> allPullSources` (built by
  `TerritoryControl.buildPullSources()`) instead of otherAnchors/boundedRivalAnchors; owned
  tiles are contested (strictly stronger pull takes over, both sides compute identical pulls so
  ownership converges), castle keeps + every town's inner half-radius are hard floors, and
  Spawn projects nothing at all anymore (its leftover central bubble was the user-reported
  unclaimed circle). `load()` additionally calls `TerritoryControl.repairMissingCapitals()`.
  Capitol-polish round (2026-08-09): `load()` calls `TownRestoration.repairCapitolState()` and
  `TownRestoration.updateTownLifeBonus(false)` right after `rebuildPlayerTownVision()` - both
  read pointOfInterestChanges flags, so they share its both-halves-loaded requirement; both
  idempotent and inert without the mod plane's config flags. Outlook round (2026-08-09, same
  day): `rebuildPlayerTownVision()` doubles a town's cached vision radius if
  `EconomyBuildings.OUTLOOK` is registered there - vision only, `townTerritoryRadius` (ownership/
  expansion) itself is untouched, so a town can see twice as far without claiming twice as much
  ground. FoW-repair round (2026-08-09 playtest): the Revealed-tier model redesigned - new
  `getTownVisionRadiusTiles()` (Capitol capped at its keep radius, NOT the huge mirrored
  territory radius that collapsed fog to 2 states; Outlook x2 town / x3 Capitol),
  `isPersistentlyRevealed()` (owned-ground player-biome-bit check + town circles, split from
  `isCurrentlyVisible()` so the minimap can use it), lazily-cached `playerBiomeBit()`;
  `updateFogOfWarPixmap()` gained the third full-brightness tier for persistently-Revealed
  tiles; `refreshWorldMapMarkers()` ends with `rebuildFogOfWarPixmap()` (the fog overlay holds
  tile COPIES - markers redrawn only into biomeImage never reached it, the missing-Capitol-icon
  bug); new `refreshFogInRadius()` (re-tier fog + re-bake ground for a radius whose Revealed
  state changed without any explored[][] change - Outlook build/destroy). `WorldSave.load()`
  re-derives the fog overlay once after the vision cache is real (World.load()'s own rebuild
  runs before pointOfInterestChanges loads).
- **`forge-gui-mobile/src/forge/adventure/data/BiomeData.java`** — bug fix in
  `getEnemy()`'s weighted-random selection: a biome whose only matching enemies all have 0 spawn
  weight used to always pick the same one deterministically instead of randomly (found via the
  `player` placeholder biome, #7, but a general engine bug, not player-biome-specific).
  **Second bug fix (2026-08-08, duplicate-town-names report)**: `getNewTownName()` now reloads the
  full `town_names_<biome>.txt` list when the pool runs dry instead of returning null (which
  silently baked the POI template's generic name - "Waste Town Generic" - into every remaining
  town); added `resetTownNamePool()` for World-gen's placement-restart path (see `World.java`).
- **`forge-gui-mobile/src/forge/adventure/data/BiomeStructureData.java`** — **bug fix**: the
  `BiomeStructureData(BiomeStructureData)` copy constructor copied every field except `N` (WFC
  pattern size), silently reverting a clone to the class default (`3`) instead of the source's real
  value. Found via the generate-as-wasteland redesign's `World.cloneStructures()` (#7, 2026-08-06 -
  see `MOD_CHANGELOG.md`), the first real caller of this constructor, so fixing it carried no risk
  to any existing behavior.
- **`forge-gui-mobile/src/forge/adventure/stage/WorldBackground.java`** — added chunk-reload/tile-
  patch hooks (`onTileRevealed`, `reloadChunkObjects`) so a live terrain repaint (#7) or fog
  reveal (#3) shows up immediately instead of only on map reload. FoW discovery-flash round
  (2026-08-10): the POI-discovery `revealArea()` call now flags each newly-revealed tile via
  `World.temporarilyReveal()` in its callback, and `draw()` calls the new
  `World.tickTemporaryReveals()` once per frame to decay/repaint expired flashes (#3). Playtest
  round (2026-08-11, three bugs in the same loop): skips POIs with `getActive() == false`
  (Dungeon Rotation's reserve pool - was lifting fog around empty reserve slots, #15); split
  `DISCOVERY_REVEAL_RADIUS` into a town/capital/castle tier (unchanged, 11) and a smaller
  dungeon/cave/sideboss tier (6, ~50%); centers the vision-radius proximity check and the reveal
  burst on the POI's bounding-rectangle CENTER instead of its raw top-left position - a large
  town/capital sprite's top-left corner could sit many tiles from where the player can actually
  stand, silently making the discovery trigger far harder to reach for big POIs than small ones
  (explains "dungeons lift fog, towns don't"). **Playtest round 2, same day: center-distance
  itself was still inconsistent** ("have to approach the town from just the exact angle... maybe
  create a radius around the town to trigger" - user's own diagnosis, correct). A large sprite's
  center can be several tiles from an edge the player is standing right next to, so the effective
  trigger radius varied by approach angle and footprint size. Replaced with proper closest-point-
  on-rectangle distance (clamp the player's world position into the POI's bounds, then measure
  from there) - 0 distance anywhere inside/touching the footprint, true edge distance outside it,
  identical to the old math for a 1-tile POI. The reveal-burst CENTER is still the rectangle
  center (unchanged) - only the trigger gate's distance metric changed.
- **`forge-gui-mobile/src/forge/adventure/stage/MapSprite.java`** — overworld POI icons (towns/
  castles) now hide until the tile under their *center* has been explored (fog of war, #3) -
  previously checked the sprite's bottom-left corner, which could leave multi-tile buildings'
  icons permanently hidden even while standing at the entrance.
- **`forge-gui-mobile/src/forge/adventure/stage/PointOfInterestMapSprite.java`** — draws the
  broken-town overlay (#2) when applicable; also a bug fix - used to cache a POI's sprite once at
  construction (a `final` field), so Territory Control's `transformInto()` (#7, a POI becoming a
  different town) couldn't ever update the rendered icon. Now reads the sprite fresh each frame.
  Guard indicator (#22, from the other machine's 2026-08-11 round, undocumented here until now):
  `drawGuardIndicator()` draws a small tier icon in the sprite's corner for a hired guard.
  **Playtest round 2 fix, same day:** it only ever drew the single strongest guard's icon, even at
  the Capitol (which allows 2 hired guards) - user report: "only 1 icon appeared... will need up
  to two icons." Now loops every hired guard and draws one icon per, offset left-to-right.
  **Round 3, same day:** each icon now draws at a fixed 12x12 (`GUARD_ICON_DRAW_SIZE`) instead of
  the source art's native 8x8 - user: "a little small... let's try 12x12." Draw-time upscale only,
  source crop untouched.
- **`forge-gui-mobile/src/forge/adventure/pointofintrest/PointOfInterest.java`** — added
  `transformInto(PointOfInterestData, Random)` (#7): rebuilds a POI's sprite/rectangle/active-state
  from a *different* data definition in place, used when a captured neutral town becomes a real
  instance of the capturing color's own town. Night round (2026-08-08): new
  `transformInto(..., preserveDisplayName)` overload - the unconditional `displayName = null`
  wipe was why the gen-time sweep and every mage capture reverted uniquely-named towns to their
  template's generic name; the sweep and captures now preserve, capital promotion still resets.
- **`forge-gui-mobile/src/forge/adventure/pointofintrest/PointOfInterestChanges.java`** — added
  persisted per-town fields: `bankBalance`, `economyBuildingObjectIds` (#10). Playtest round
  (2026-08-09): persisted `pinnedShopNames` (objectId -> ShopData name; missing-key-safe load) -
  pins a shop object to a fixed shop identity instead of the per-load random roll, used by the
  Capitol migration to carry the source town's exact shop lineup over. Capitol reserved-slots
  round (2026-08-10): new `removePinnedShopName(objectId)` - lets `TownRestoration.
  repairCapitolState()` strip a stale pin an older migration left on Armory/Booster so those slots
  self-correct back to their tmx-defined shop type on an existing save (#13). Building-level round
  (2026-08-11, #8/#13): persisted `buildingLevels` (objectId -> level, missing = 1) for Arena/
  Armory L1->L2 upgrades. Guard round (same day, #22): persisted `guardTiers`/`guardLastPaidDay`
  parallel lists. Archaeologist round (same day, #24): persisted `archaeologistExpeditionSentDay`
  (plain int, -1 = none active, missing-key-safe load like `bankBalance`) - not objectId-keyed like
  the two fields just above, since there's only ever one Archaeologist per save. Armory Re-roll
  round (2026-08-11, round 7): persisted `shopManualRerollLastDay` (objectId -> day, missing-key-
  safe load) plus `canManuallyRerollShop()`/`manuallyRerollShop()` - a SEPARATE cooldown clock from
  the pre-existing `shopLastRefreshDay`/`getWeeklyShopSeed()` (the automatic weekly reseed), per
  user spec that the manual button's cooldown must not interact with the automatic one's own
  schedule.
- **`forge-gui-mobile/src/forge/adventure/scene/TileMapScene.java`** — `enter()`'s
  `isAutoHealLocation()` block grants a Partner-tier free overheal (#1, from the other machine's
  2026-08-10 round). Playtest round (2026-08-11) fixed a real bug found there: the base
  `Current.player().fullHeal()` a few lines above was unconditional - the reputation check only
  ever gated the Partner BONUS on top of it, never the base heal itself, so life was still fully
  restored entering an Unhappy- or War-tier town (user report: "still getting life restored...
  unhappy/at war with"). Now shares one `repColor`/`playerOwned`/status-derived gate
  (`ColorReputation.isFreeHealBlocked()`, new) with the Partner-bonus check just below it.

### Towns, shops, and buildings (Town Reconstruction / Economy Buildings, #2 & #10)
- **`forge-gui-mobile/src/forge/adventure/character/ShopActor.java`** — heaviest content-logic
  file after World.java: ruin/rebuilt-building icon rendering, special/armory shop dialogs, shop
  overhead-tile hide/restore. Color Reputation (#1) added a third factor to `getPriceModifier()`
  (`colorReputationModifier()` - tier-based card price scaling in a color's towns).
  Capitol-polish round (2026-08-09): new `fixedShop` flag (set by MapStage from the tmx
  property) - a fixed shop repairs via the simple dialog only (never the economy-building
  conversion menu) and draws no overlay icon once rebuilt (its hut art is baked into the map).
  Outlook/Teleporter/Destroy round (2026-08-09, same day): `onPlayerCollide()`'s economy-type
  switch gained OUTLOOK and TELEPORTER cases. Playtest round (same day, user revision): the
  short-lived Enter/Destroy/Leave pre-gate is gone again - every plain shop goes straight into
  RewardScene, which now hosts the Destroy button itself, driven by the new
  `isDestroyable()` (plain/booster in wasteland towns; Armory/fixedShop excluded).
  Teleporter-animation round (2026-08-10): `draw()` intercepts the TELEPORTER economy type before
  the generic building-sprite path, picking `EconomyBuildings.getTeleporterClosedSprite()` or the
  current frame of `getTeleporterActiveAnimation()` (based on `isTeleporterNetworkActive()`) via a
  new per-actor `teleporterAnimTime` clock ticked in `act()`. Archaeologist round (2026-08-11,
  playtest round 2): `onPlayerCollide()`'s economy-type switch gained an ARCHAEOLOGIST case
  (`EconomyBuildings.openArchaeologistDialog()`) - the building moved from a standalone map
  object to a Utility-submenu economy type this same round, so it now flows through this switch
  like Outlook/Teleporter instead of its own dedicated `OnCollide`/MapStage case.
  **Shop Type Re-Roll round (2026-08-11, round 8):** added `setShopData(ShopData)` (a mutator
  alongside the pre-existing `getShopData()` - lets `RewardScene.promptRerollShopType()` swap this
  actor's identity in place after a re-roll instead of tearing down and reconstructing the whole
  actor). `getMapStage()` (pre-existing) is now also called from `RewardScene` for the same
  feature, reachable since it's `public`.
- **`forge-gui-mobile/src/forge/adventure/scene/RewardScene.java`** — already hosted the Destroy
  button (see ShopActor's entry above). Playtest round (2026-08-11): new `armoryRestockNote()`
  appends a small "Restocks weekly" line to the shop header for Armory-type shops
  (`EconomyBuildings.isArmoryShop()`) - user request, since Armory shops restock via the weekly
  reseed instead of the ordinary paid restock button and had no on-screen indication of that.
  **Playtest round 2, same day:** exact wording corrected to "Inventory will refresh weekly" per
  the user's precise request (also independently extended to land shops by the other machine's
  session the same day - see the other `RewardScene.java` entry further down).
- **`forge-gui-mobile/src/forge/adventure/character/OnCollide.java`** — added an optional
  town-restoration-gated constructor overload (Job Board building specifically) - the original
  single-arg constructor is unchanged/still used everywhere else unmodified. Capitol-polish
  round (2026-08-09): a destroyed gated building in the Capitol draws the 32x32 broken-shop art
  (same placement as ShopActor's) instead of the translucent RubbleOverlay; regular towns keep
  the overlay. Playtest round (same day): `withRebuiltIcon(TextureRegion)` builder - drawn
  over-footprint once the gated building is rebuilt in a wasteland-template map (a restored
  Arena/Spellsmith was invisible, no baked art exists there); null (the default) draws nothing.
- **`forge-gui-mobile/src/forge/adventure/character/QuestActor.java`** — same gating pattern as
  `OnCollide.java` for the Job Board's own quest-giver interaction, plus triggers the terrain
  recolor prototype (#7) once a town's restored. Night round (2026-08-08): a RESTORED wasteland
  town's Job Board now opens `TownRestoration.openJobBoardMenu()` before the quest offer - quest
  flow extracted to `showQuestBoard()`, stock towns unchanged. Night round 2: the menu only
  shows while the Capitol upgrade offer is live (`shouldShowJobBoardMenu()`) - once a Capitol
  exists, straight to quests.
- **`forge-gui-mobile/src/forge/adventure/stage/MapStage.java`** — largest diff after World.java:
  shop overhead-tile detection/hide (`findOverheadTiles`/`setShopOverheadTilesHidden`), sign
  visibility live-updates. Capitol-polish round (2026-08-09): the "shop" case reads the new
  `fixedShop` tmx property onto `ShopActor.setFixedShop()`. Playtest round (same day): the
  "inn" case reverted to the ungated single-arg OnCollide (user decision - the Inn always works,
  never rubble); "arena"/"spellsmith" cases attach `withRebuiltIcon()` art; the "shop" case
  honors `PointOfInterestChanges.getPinnedShopName()` over the random roll (roll still executes
  so the shared world RNG advances identically); new `getShopActors()` accessor (the Capitol
  migration snapshots the live rolled shops). **Bug fix, same round**:
  `showDialog()` fully duplicated `GameStage.showDialog()`'s body instead of calling it, which
  meant the 2026-08-08 "stop player movement on dialog open" fix (added only to the base class)
  never ran for any shop/building/quest interaction - every one of those goes through THIS
  override, not the base class. Reduced to `super.showDialog()` + the one line MapStage actually
  adds (`freezeAllEnemyBehaviors = true`), so any future base-class dialog fix reaches MapStage
  automatically. Dropped the now-unused `Actions` import. Stone-pickup round (2026-08-10): the
  single-resource walkover fast-path switch (`onActing()`'s `RewardSprite` branch) gained a
  `case Stone:` alongside the existing Life/Shards/Gold group - Stone has no font-registered
  `[+Stone]` bracket icon, so its status message passes a null icon rather than a broken glyph.

### HUD & UI
- **`forge-gui-mobile/src/forge/adventure/stage/GameHUD.java`** — clock readout (#6), resource
  panel (Wood/Stone, #9), fog-of-war/speed-toggle debug checkboxes (#3/#6). Territory Control (#7)
  added a per-mage colored minimap dot (`updateMageMinimapMarkers()`, dynamic set mirroring the
  existing `miniMapPlayer` marker) and a `worldStandingsActor` button (opens the new
  `WorldStandingsScene`) - replaced an earlier `TownCountActor` HUD panel version of this, since
  removed. 2026-08-08 tighten-up per user mockups: Day/Time + 100x/Wait consolidated into one
  column under the Zoom button, World button moved up in line with the top menu bar (left of the
  ESC/menu button, no longer chained off bookmarkActor). Territory Control playtest round 7 (same day) wired `worldStandingsActor`'s
  visibility into the existing `showHideMap(boolean)` method (right next to `bookmarkActor`/
  `exitToWorldMapActor`'s own `MapStage.isInMap()`-based toggles) so the button hides while inside
  a town instead of staying visible everywhere. **Bug fix, same-round as the day-reset fix below**:
  the corner minimap's `Texture` (`refreshMiniMap()`) was only ever re-snapshotted from `World.
  biomeImage` on HUD `enter()` - fine for a town capture, but daily Territory Control expansion
  keeps editing that same Pixmap in the background while the player just stays on the overworld
  screen, so the displayed minimap silently went stale ("map details wiped out by the expansion
  creep"). `draw()` (already runs every frame) now compares `World.getCurrentDay()` against a new
  `lastMiniMapRefreshDayCount` field and calls `refreshMiniMap()` whenever it changes - once per
  in-game day, not per frame. Cross-machine review round (2026-08-07): `touchDown()` now forwards
  clicks landing on the visible World standings button before the minimap-bounds interception
  swallows them (the relocated button overlaps the minimap's corner in desktop landscape - its
  left half was click-dead); both standings-button visibility gates accept
  `isTerritoryControlEnabled() || ColorReputation.isEnabled()` (the standings page is reputation's
  only UI, documented as working without territory control); `MAGE_MARKER_COLORS` exposed via a
  new public static `getMageMarkerColor()` so MapViewScene's zoomed-view mage dots share the exact
  palette. Five-request round: `updateMageMinimapMarkers()` gates each dot on
  `World.isCurrentlyVisible()` at the mage's tile (fog-of-war Revealed rule - always true with fog
  off, preserving old behavior). `addNotification()` briefly used a `[BLACK]`-markup-prefix + WHITE tint
  (to let inline colors through the multiplying black tint) and was REVERTED to plain tint-BLACK:
  quest texts carry their own style/reset tokens, and any reset snapped the remainder white (a
  reported regression) - notification emphasis was bold-only for a round. Six-report round
  (2026-08-08 evening): added an opt-in `addNotification(text, authoredMarkup)` overload - WHITE
  tint for that one message only, caller must open with a color tag and fully author the string
  (no quest payloads); ordinary notifications keep the safe tint-BLACK path. Used by the
  mage-attack warning's `[RED]PLAYER OWNED TOWN!` (the bold-caps version rendered as smeared
  double-struck glyphs at pixel-font size).
- **`forge-gui-mobile/src/forge/adventure/data/AdventureQuestData.java`** — the stage-activation
  notification in `activateNextStages()` now opens with a "Quest Updated:" header (2026-08-08).
  It fires on accept AND whenever a later objective unlocks mid-quest (any quest event can trigger
  it - at 100x fast-forward even a roaming monster's despawn sweep); without the header those
  mid-quest firings read as unexplained quest popups (user report).
- **`forge-gui-mobile/src/forge/adventure/scene/QuestLogScene.java`** — Side-quest timers (#16):
  both quest-name labels (list + detail) append `QuestExpiry.questLogSuffix()` - "(N days left)",
  empty when no timer applies.
- **`forge-gui-mobile/src/forge/adventure/scene/SettingsScene.java`** — fog-of-war on/off setting
  (#3, a real Settings-screen checkbox, not just the in-game HUD debug toggle).
- **`forge-gui-mobile/src/forge/adventure/scene/MapViewScene.java`** — extracted the minimap
  texture refresh into its own `refreshMap()` method so the fog-of-war debug toggle can force an
  immediate update instead of waiting for the next scene entry. Territory Control (#7, per direct
  request): one colored dot per in-flight capture mage on the zoomed map view, rebuilt from
  `WorldStage.getTerritoryMages()` on every `enter()`, tinted via `GameHUD.getMageMarkerColor()`
  (shared palette with the corner minimap's dots), riding the same zoom transform as the player
  marker/quest labels, removed in `done()`. Five-request round: the same
  `World.isCurrentlyVisible()` fog-of-war gate as the corner minimap - a mage outside Revealed
  territory gets no dot here either. Night round (2026-08-08): the Details overlay additionally
  labels every VISITED town/capital with its display name (visited-only to keep 400+ labels off
  the map at once).
- **`forge-gui-mobile/src/forge/adventure/data/SettingData.java`** — added the persisted
  `fogOfWarEnabled` setting field backing the above.
- **`forge-gui/res/languages/en-US.properties`** — **the one shared (non-mod-plane) asset file
  that had to be edited directly** - Forge's localization strings aren't overridable per-plane, so
  3 new label keys (`lblFogOfWar`, `lblFastTimeToggle`, `lblWait`) were added directly to the
  shared file. Low conflict risk (pure additions at the end of a large file, plus later value edits
  - `lblFastTimeToggle`'s text updated 10x -> 50x -> 100x across two rounds to match the actual
  multiplier, most recently per an explicit request to speed up Territory Control playtesting, then
  changed a 4th time (2026-08-14) from the numeric "100x Speed" wording to plain "Speed-Up" - the
  multiplier itself moved into `tuning.json` the same round (`TuningData.speedUpMultiplier`,
  defaulted down to 50x from the hardcoded 100x) and no longer belongs in the button's own label at
  all, since it's now a tunable rather than a fixed number) but worth knowing this is the one
  exception to "everything lives in the mod folder." **Deploy note**:
  unlike `.java` changes, this file isn't bundled inside the jar - Forge loads it directly from
  `res/languages/en-US.properties` next to the executable, so a source edit here needs a plain file
  copy to the deploy directory, not a `jar uf`.

### Player / config
- **`forge-gui-mobile/src/forge/adventure/util/Paths.java`** — added `GOLD_ATLAS = "sprites/
  gold.atlas"` (2026-08-09, #14) - the stock Gold-pickup atlas, read by
  `WorldStage.getGoldSparkleAnimation()`; no path previously pointed at it from mod code.
- **`forge-gui-mobile/src/forge/adventure/player/AdventurePlayer.java`** — added Wood/Stone
  resource fields alongside existing Gold/Shards (#9), same pattern (get/add/take/onChange).
  Color Reputation (#1) added `colorReputationHalfPoints` (Map<String,Integer>, save/load/clear
  like the resources) with get/add accessors, plus one `ColorReputation.applyStartingDeckBonus()`
  call in `create()` right after the starting deck's color identity is set. Territory Effects
  (#17, 2026-08-09): persisted `townLifeBonus` field + `applyTownLifeBonus(target)` (applies
  only the delta to maxLife; gain heals by the gain, loss clamps life to new max, never below
  1) - driven by `TownRestoration.updateTownLifeBonus()`. Stone-pickup round (2026-08-10):
  `addReward(Reward)` gained `case Stone: addStone(reward.getCount()); break;`.
  Progressive Set Unlocks round (2026-08-12, #4): new persisted `unlockedEditions` (`Set<String>`),
  `researchEditionInProgress`/`researchStartDay` (single-slot timer, mirrors the Archaeologist's
  pattern) with get/set/`clear()` wiring; `checkResearchCompletion(int)` auto-unlocks once
  `RESEARCH_DAYS` (7) elapse, called both lazily (`ResearchScene.enter()`) and from
  `EconomyBuildings.processDaysPassed()`'s daily tick. `create()` gained difficulty-scaled starting
  `unlockedEditions` seeding from this plane's own `starterEditions` list.
- **`forge-gui-mobile/src/forge/adventure/util/Reward.java`** — added `Stone` to the `Type` enum
  (2026-08-10) - a walkover-only reward type (see `MapStage.java`'s onActing() entry); no new
  constructor needed, the existing `Reward(Type, int)` covers it like `Life`/`Shards`.
- **`forge-gui-mobile/src/forge/adventure/data/RewardData.java`** — added a `"stone"` case to
  `generate()`'s switch (2026-08-10), mirroring the existing `"shards"` case exactly
  (`new Reward(Reward.Type.Stone, count + addedCount)`). **No-duplicate-items bug fix (2026-08-11,
  round 8, user report: "There should never be 2 of the same item for sale"):** the `"item"` case's
  `itemNames` branch used to pick `count+addedCount` times independently at random with no
  exclusion tracking, so the same name could (and did) come up twice in one roll. Now shuffles a
  copy of the pool once (`Collections.shuffle(list, rewardRandom)` - same seeded `Random`, so
  determinism/stability across visits is unaffected) and takes the front, gracefully capping at
  the pool's own size if asked for more unique items than it actually has.
- **`forge-gui-mobile/src/forge/adventure/data/ConfigData.java`** — added the opt-in mod flags:
  `fogOfWarEnabled`, `dayNightCycleEnabled`, `townReconstructionEnabled`, `territoryControlEnabled`,
  `colorReputationEnabled`, `resourceSpawnsEnabled`, `dungeonRotationEnabled`, `sideQuestTimerEnabled`,
  `resourceLootVarietyEnabled`, and (2026-08-12, #4) `editionProgressionEnabled` (all default
  `false` - see `CLAUDE.md`'s ground rules for why this pattern matters).
- **`forge-gui-mobile/src/forge/adventure/stage/ConsoleCommandInterpreter.java`** — debug
  console additions: `count towns` (#7 - was missing from this doc until now, added when the
  `give rep` change touched the same file), `give rep <color> <amount>` (#1,
  net-zero-preserving reputation shift for tier testing), `give wood`/`give lumber`
  (alias)/`give stone` (#9), and `spawn resource` (drops one random resource pickup next to the
  player for testing the spawn mechanic without hunting the map).
- **`forge-gui-mobile/src/forge/adventure/scene/DuelScene.java`** — Color Reputation (#1): one
  guarded hook at the top of `afterGameEnd()` (`winner && !isArena && eventData == null`) calling
  `ColorReputation.onPlayerWonDuel()`. That method is the single funnel every duel's end passes
  through, and the only spot where win/loss, Arena, and Inn-event status are all knowable at once
  - if upstream restructures `GameEnd()`/`afterGameEnd()`, this hook needs to move with whatever
  replaces that funnel. Ante-off round (2026-08-11, #20 - missing from this doc until now, added
  when the same file was touched again for Arena Challenge): `initDuels()`'s `rules.
  setPlayForAnte(...)` now also checks `!enemy.getData().noAnte` (new field, see `EnemyData.java`
  below) alongside the existing global Ante preference. **Deck Tester round (2026-08-11, round 3):**
  the AI-deck-resolution ternary in the match-building loop gained a new branch, inserted
  immediately before the chain's final `else` (after the pre-existing `chaosBattle`/
  `arenaBattleChallenge`/`eventData != null` checks): `else if (currentEnemy.fixedDeck != null)
  deck = currentEnemy.fixedDeck;` - lets `ArenaScene.launchDeckTester()` hand the AI an exact,
  arbitrary `Deck` object (see `EnemyData.java` below) instead of anything resolved by name.
- **`forge-gui-mobile/src/forge/adventure/data/EnemyData.java`** — added `noAnte` (boolean, default
  false, #20) - lets a single fight force Ante off regardless of the player's global preference,
  without touching that preference itself; set only on a per-fight clone (`new EnemyData(enemyData)`
  then `.noAnte = true`), never on the shared roster data, so the enemy's other appearances are
  unaffected. Read by `DuelScene.initDuels()` (see above). `gamesPerMatch` (the field
  `ArenaScene.loadArenaData()`'s new `isChallenge` flag also overrides per-fight, same clone
  pattern) is pre-existing stock Forge, not a mod addition - confirmed via `git log -p` on this
  file, listed here only for context since both fields are touched by the same clone-and-override
  pattern. **Deck Tester round (2026-08-11, round 3):** added `fixedDeck` (`transient Deck`,
  default null) - when set, `DuelScene`'s AI-deck-resolution logic uses this exact `Deck` object
  verbatim instead of resolving one from `deck[]`/`randomizeDeck`/`copyPlayerDeck`. Deliberately
  NOT copied by the `EnemyData(EnemyData)` clone constructor (unlike `noAnte`/`gamesPerMatch`,
  which are) - always set explicitly on a fresh per-fight clone right after construction, in
  `ArenaScene.launchDeckTester()`, never expected to propagate through a second-generation clone.
  **Upgrade/Challenge-toggle round (2026-08-11, playtest round 2):** the pre-entry
  MapStage dialog (`EconomyBuildings.openArenaEntryDialog()`) that used to gate Upgrade/Challenge
  choices before ArenaScene even loaded is gone (user request: "have the Upgrade be an option
  inside the arena interface vs. a gating menu"). New entry point `enterArenaBuilding(MapStage,
  int objectId, String regularJson, String challengeJson)` called directly from `MapStage`'s
  "arena" case - stashes the stage/objectId/both raw ArenaData JSON strings, then loads the
  Normal pool by default. Two new programmatic buttons (`arenaUpgradeButton`/
  `arenaModeToggleButton`, same positioned-above-doneButton pattern `RewardScene`'s
  guardsButton/upgradeButton established) live directly on the Arena screen: `promptUpgradeArena()`
  (confirm dialog, spends `EconomyBuildings.BUILDING_UPGRADE_COST`, sets the building to Level 2)
  shown while level < 2, `toggleArenaMode()` (re-parses whichever JSON isn't currently showing and
  calls the existing `loadArenaData()` again - a full bracket reload, same as a fresh entry) shown
  once level >= 2 AND a Challenge pool exists for this arena - single button whose label reflects
  which mode it would switch TO. `refreshArenaBuildingButtons()` hides both once a run is actually
  in progress (`arenaStarted || roundsWon != 0`) - upgrading or switching modes mid-tournament was
  never a sensible thing to allow.
- **`forge-gui-mobile/src/forge/adventure/scene/ArenaScene.java`** — Ante-off round (2026-08-11,
  #20 - also missing from this doc until now): `loadArenaData()`'s enemy-cloning loop sets
  `arenaEnemyData.noAnte = true` on each per-fight `EnemyData` clone. Challenge Arena round (same
  day, later): new `loadArenaData(ArenaData, long, boolean isChallenge)` overload (the original
  2-arg signature delegates to it with `false`) - when `isChallenge` is true, the same clone also
  gets `gamesPerMatch = 1` forced, overriding the roughly 30% of the Challenge enemy pool that
  otherwise defaults to best-of-3 in `enemies.json`. **Deck Tester round (2026-08-11, round 3):**
  new `deckTesterButton` (visible whenever `level >= 2 && !midMatch`, independent of whether this
  arena has a Challenge pool), `promptDeckTester()`/`promptDeckTesterAiDeck()` (two sequential
  raw-`Dialog` deck pickers, same pattern `EconomyBuildings.buildManageGuardsDialog()` established),
  and `launchDeckTester(int playerDeckIndex, int aiDeckIndex)` - clones the stock "Doppelganger"
  enemy (colorless, already-valid sprite, ships with `copyPlayerDeck: true` as its own "mirror
  match" flavor - reused as a low-risk shell) and overrides `copyPlayerDeck = false`, `fixedDeck`
  (see `EnemyData.java` above), `nameOverride = "Deck Tester"`, `noAnte = true`,
  `rewards = new RewardData[0]`; temporarily swaps `AdventurePlayer`'s selected deck slot around the
  `initDuels()` call (restored immediately after - safe since `initDuels()` copies the deck
  synchronously) so the player pilots a specific saved deck rather than whichever one happens to be
  globally selected. New `deckTesterMatch` boolean guards the top of the existing `setWinner()`
  override (`DuelScene.afterGameEnd()`'s automatic `IAfterMatch` callback fires for ANY duel
  launched while this scene was active, not just bracket duels) - when true, skips all bracket-
  advancement logic entirely instead of indexing into bracket state that has nothing to do with a
  Deck Tester match. **Pricing round (2026-08-11, round 4):** `arenaUpgradeButton`'s cost now flows
  through `EconomyBuildings.scaledCost()` (difficulty price multiplier) with `[+Gold]` markup
  instead of the raw `BUILDING_UPGRADE_COST` constant - at 3 sites: the constructor's initial label,
  `promptUpgradeArena()`'s cost variable, and (new) a text refresh inside
  `refreshArenaBuildingButtons()` itself, since the label previously never updated after
  construction and would otherwise go stale once the cost became difficulty-dependent.
  **Off-screen bug fix (2026-08-11, round 5 - user report: "Upgrade / switch Arena button is off
  the screen on the left"):** the 3 wide programmatic buttons (`arenaUpgradeButton`/
  `arenaModeToggleButton`/`deckTesterButton`) were positioned by right-aligning to `doneButton`'s
  right edge while sized at 2.2x its width - fine for a button narrower than `doneButton`, but
  `doneButton` sits at `x=5` in the 480-wide `ui/arena.json` canvas (near the left edge), so a
  wider button right-aligned to it computed a negative left-edge X (`5 + 48 - 105.6 = -52.6`),
  genuinely off-canvas, not just visually cramped. Fixed by left-aligning all three to
  `doneButton.getX()` instead (confirmed ~325 units of open space to the right before the
  gold/start buttons at x=380) and replacing the doneButton-relative width multiplier with an
  explicit `ARENA_WIDE_BUTTON_WIDTH = 220f` constant, plus a `[%80]` text-scale prefix on the
  longer labels for margin. **Round 7 (2026-08-11), user report** ("the arena text for Deck Tester
  should be moved to next to Arena type switch"): `deckTesterButton` moved off its own separate row
  (which overlapped the bracket-tree view) onto the SAME row as `arenaModeToggleButton`, immediately
  to its right - new `ARENA_DECK_TESTER_BUTTON_WIDTH = 140f` (narrower than the other two, "Deck
  Tester" is a short label) sized to fit the space actually left between the toggle's right edge and
  the gold/start buttons at x=380. Safe since the two buttons that could occupy that row
  (`arenaUpgradeButton` and `arenaModeToggleButton`) are already mutually exclusive by level, and
  `deckTesterButton` is only ever visible under the SAME level condition as the toggle.
- **`forge-gui-mobile/src/forge/adventure/character/EnemySprite.java`** — added `territoryTarget`/
  `territoryColor` fields (#7, null for every ordinary enemy - only set on a Territory Control
  mage). Combat gold variance (2026-08-09, #9): new `applyGoldVariance()`, called at the end of
  `getRewards()` - 25% of any Gold reward in the assembled list is swapped for an immediate Wood/
  Stone grant (see MOD_CHANGELOG.md for why this bypasses Reward.Type entirely).
  Progressive Set Unlocks round (2026-08-12, #4): `getRewards()`'s standard-rewards loop now
  restricts `data.rewards` (never `this.rewards`) to the defeated enemy's color's edition shard via
  `EditionProgression.restrictToEditions()`, skipped for bosses/quest-tagged enemies.
- **`forge-gui-mobile/src/forge/adventure/stage/WorldStage.java`** — day-counter-driven hooks for
  Economy Buildings (#10) and Territory Control (#7), the mage movement/arrival branch and
  `spawnAt()` (#7, also exempts a mage from the ordinary roaming-monster despawn timer - it has
  its own lifecycle). `FAST_TIME_MULTIPLIER` raised 10 -> 50 -> 100 across two rounds per request
  (#6), most recently to speed up Territory Control (#7) playtesting. Mage persistence: `save()`/
  `load()` now carry `territoryColors`/`territoryTargetIds` so a mid-flight mage survives a
  save/load (#7, cross-machine review fix). Color Reputation (#1): severe-tier entry
  interception in `handlePointsOfInterestCollision()` plus `entryBarredColor()`/
  `showCapitalTollDialog()` helpers. New public `getTerritoryMages()` accessor (#7): filters the
  `protected` enemies list down to in-flight capture mages, for MapViewScene's zoomed-view mage
  dots (different package - can't read the list directly the way same-package GameHUD does).
  Resource spawns (new feature): `ResourceSpawnActor` (lightweight pickup actor in
  `foregroundSprites`), `refreshResourceSpawnActors()` (clear-and-rebuild sync from World's spawn
  list), a per-frame `ResourceSpawns.tick()` call in `onActing()`'s moving branch, and a
  `ResourceSpawns.forceResync()` in `clearCache()`. Resource-spawn twinkle (2026-08-08 polish):
  `ResourceSpawnActor.draw()` now oscillates the `Batch`'s transient draw color's alpha instead of
  drawing at a flat alpha - doesn't touch the shared, cached `Sprite` any actor's `sprite` field
  points to. **Twinkle flicker bug fix (same day)**: the first version "restored" the batch color
  via the reference `batch.getColor()` returns - but that IS the batch's live internal `Color`
  object, already mutated by the time it was reassigned, so the twinkle's faded alpha leaked into
  every subsequent draw call that frame (towns/dungeons/rocks all pulsing). Now snapshots the
  four primitive components before `setColor` and restores from those. Six-report round
  (2026-08-08 evening): new `showQuestsFailedDialog(List<String>)` (#16) - blocking dialog in the
  war-entry/capital-toll style listing every side quest that expired on a day tick, replacing
  QuestExpiry's easy-to-miss corner toast. Dungeon rotation (#15): a
  `DungeonRotation.processDaysPassed()` call in the day-change block, and the ordinary-town entry
  bar swapped its corner notification for a real blocking dialog (`showEntryBarredDialog()`, same
  styling as the capital-toll dialog). Gold-sparkle round (2026-08-09, #14): new
  `getGoldSparkleAnimation()` (lazily builds a 4-frame `Animation<TextureRegion>` from the stock
  `sprites/gold.atlas`, same art `templeofchandra.tmx`'s Gold pickup already uses) and
  `ResourceSpawnActor` gained an optional `Animation<TextureRegion>` field - when set (Gold-type
  spawns only), `draw()` plays the real animation instead of the alpha-twinkle.
- **`forge-gui-mobile/src/forge/adventure/stage/GameStage.java`** — `showDialog()` now stops the
  player sprite's in-flight movement (2026-08-08 night 2) - the player kept walking behind every
  dialog; OnCollide's rebuild path had its own stop(), this generalizes it to all dialogs.
  Playtest round (2026-08-09), the REAL fix for that same complaint: `touchDown()`/
  `touchDragged()`/`keyDown()`'s movement handling and `act()`'s touch-steering now all check
  `dialogOnlyInput` - input is multiplexed with the HUD stage the dialog lives on, so clicking a
  dialog button also reached this stage's touchDown and walked the player toward the click; the
  earlier stop() only halted movement at dialog-open time, not input DURING the dialog.
- **`forge-gui-mobile/src/forge/adventure/scene/RewardScene.java`** — Destroy-on-shop-page
  (2026-08-09, mod feature): new programmatic `destroyButton` (built in the constructor, NOT
  added to the shared ui/items.json every plane loads; positioned above the done button), shown
  only for `type == Shop` when `ShopActor.isDestroyable()` (mod-plane wasteland shops only -
  inert everywhere else), confirm dialog via the existing `createGenericDialog()`, destruction
  routed through `EconomyBuildings.destroyShopFromRewardScene()`. **Pricing round (2026-08-11,
  round 4):** same treatment as `ArenaScene.java`'s own entry above - `upgradeButton`'s (Armory)
  cost now flows through `EconomyBuildings.scaledCost()` with `[+Gold]` markup at 3 sites
  (constructor, `promptUpgradeArmory()`, and a new text refresh alongside the existing visibility
  refresh in the shop-page switch, fixing the same "label never updates after construction" gap).
  **Round 5 (2026-08-11):** checked this file's equivalent right-aligned-to-doneButton positioning
  formula against the off-screen bug just found in `ArenaScene.java` - NOT broken here, since this
  screen's `done` button sits at `x=420` in the same 480-wide canvas (`ui/items.json`, near the
  RIGHT edge), so right-aligning a wider button to it pulls the button leftward INTO the canvas
  rather than off of it. Left the position formula alone; added the same defensive `[%80]` scale
  prefix to `upgradeButton`'s label anyway, since it carries similarly long cost text at the same
  105.6-unit width. **Armory Re-roll round (2026-08-11, round 7, user spec):** new `rerollButton` -
  Armory-only, any level (unlike `guardsButton`/`upgradeButton`, not level-gated), same right-
  aligned-to-`doneButton` positioning (proven safe above) one row higher than those two.
  `promptRerollArmory()` deliberately bypasses `shopActor.canRestock()`/`getRestockPrice()` (the
  ordinary paid-restock path Armory is blocked from as a `noRestock` shop) - gated instead by the
  new `PointOfInterestChanges.canManuallyRerollShop()` cooldown and a fixed
  `EconomyBuildings.scaledCost(ARMORY_REROLL_SHARD_COST)` shard cost, then rebuilds the displayed
  inventory the same way `restockShop()` already does. `refreshRerollButton()` toggles disabled
  state (cooldown or unaffordable) both on page load and after a successful reroll.
  **Shop Type Re-Roll round (2026-08-11, round 8, user spec):** new `shopTypeRerollButton` -
  ordinary card shops only, shares `rerollButton`'s (Armory) row position since a shop is never
  both at once. `promptRerollShopType()` delegates the actual pick/persistence/sign-swap to the
  new `MapStage.rerollShopType()` (see that file's own entry), then refreshes what this scene
  itself owns: `shopActor.setShopData()`, a fresh `changes.generateNewShopSeed()` (the old seed's
  picks don't apply to a newly-different shop identity), and `loadRewards()` to redraw the page.
- **`forge-gui-mobile/src/forge/adventure/stage/MapStage.java`** — Player Capitol round
  (2026-08-08 late night): the "arena" object case switched to the gated 3-arg OnCollide
  constructor (inn/spellsmith already used it) so an arena in a wasteland town/capital starts as
  rubble; inert outside wasteland towns. Dungeon rotation (#15): the
  defeat hook lives at the match-loss handler (the branch calling `updateQuestsLose()`) - an
  earlier `exitDungeon()` hook keyed on its `defeated` parameter never fired for concedes or
  ordinary losses with life remaining (only life-hit-zero sets it). Still BEFORE quest updates so
  the 3-attempts rule sees its protecting quest. No-op for story dungeons/bosses/towns and any
  non-rotatable POI. Arena upgrade round (2026-08-11, #8/#20): "arena" case wraps its parse-and-
  switch-scene logic in a `Runnable` passed to `EconomyBuildings.openArenaEntryDialog()` instead of
  running unconditionally on collision. Challenge Arena round (same day, later): that call gained a
  second `Runnable` parameter, `prop.containsKey("arenaChallenge") ? (...) : null` - null wherever
  a town's arena has no `arenaChallenge` tmx property (every arena but the player Capitol's).
  Archaeologist round (same day): new `"archaeologist"` case (#24), same gated 3-arg `OnCollide` +
  `withRebuiltIcon()` pattern as "arena"/"spellsmith". **Playtest round 2 (2026-08-11, same day,
  different machine):** the standalone `"archaeologist"` case above is GONE - user request to
  move it into the Utility build-submenu instead (see `EconomyBuildings.ARCHAEOLOGIST`); the
  `archaeologist.tx` template and its `player_capital.tmx` object are both deleted, no longer
  referenced anywhere. The "arena" case's `EconomyBuildings.openArenaEntryDialog()` pre-entry
  dialog wrapper is also gone (see `ArenaScene.java`'s own new entry below) - collision now calls
  `ArenaScene.enterArenaBuilding()` directly and switches scene immediately, no dialog stop first.
  "spellsmith" case fixed to actually call `EconomyBuildings.getSpellsmithSprite()` - it had been
  hardcoded to the generic `SpecialShop` placeholder ever since the real Spellsmith art was added
  in an earlier round; the atlas region was already correct, this case just never read it.
  **Round 8 (2026-08-11), two user-driven changes to the shared "shop" case:** (1) Armory
  level-based slot count (user spec: "Lvl 1 has 6 and level 2 has 8. Regardless of where they
  are") - after `shopList` is resolved, a new check (`shopList.endsWith("Equipment") ||
  shopList.startsWith("Armory")`, mirroring `EconomyBuildings.isArmoryShop()`'s own logic since
  that method needs an already-resolved `ShopData` not available yet here) appends `"L2"` once
  `changes.getBuildingLevel(id) >= 2`, redirecting to a matching higher-count `shops.json` entry -
  covers both the Town's `"Equipment"` shop and the Capitol's tiered `"ArmoryCommon"`/etc names
  with one check. (2) Shop Type Re-Roll (user spec: re-roll a card shop's type for 50 shards,
  updating its sign) - new fields `shopCandidatePools` (objectId -> the raw, unfiltered comma-list
  a shop object could roll from, captured once at load right where `possibleShops` is already
  computed - naturally excludes Armory/land shops, whose tmx properties are always single names,
  and Rotating shops, which have their own separate date-seeded mechanism) and `shopSigns`
  (objectId -> the actual on-screen sign `TextureSprite`, captured where it's created, so a re-roll
  can swap its artwork live). New public `isShopTypeRerollable(objectId)`/`rerollShopType(objectId,
  currentName)` - the latter picks a new `ShopData` from the recorded candidate pool (excluding the
  current name), pins it via the pre-existing `PointOfInterestChanges.setPinnedShopName()`
  mechanism (previously only used by the Capitol migration), and calls the sign sprite's new
  `setRegion()`.
  Progressive Set Unlocks round (2026-08-12, #4): new `"researchlab"` case - a PLAIN single-arg
  `OnCollide` (no gate, no `withRebuiltIcon()` - see `MOD_CHANGELOG.md` for why, its art is already
  baked into this map's tile layers). The `"shop"` case's card-roll generation now clones and
  restricts each `RewardData` to a color/player edition list via `EditionProgression.
  restrictToEditions()` before calling `.generate()`, gated on `World.isEditionProgressionEnabled()`.
- **`forge-gui-mobile/src/forge/adventure/character/TextureSprite.java`** — mod addition (round 8,
  Shop Type Re-Roll): `region` un-`final`'d and a new `setRegion(TextureRegion)` mutator added -
  previously an immutable-region sprite with no way to change its art after construction, needed
  so `MapStage.rerollShopType()` can update a shop's sign live instead of tearing down and
  recreating the actor. Deliberately doesn't touch width/height (position/footprint stays put,
  only the artwork changes) - the one pre-existing caller never needed to resize after construction
  either, so this isn't a behavior change for it.
- **`forge-gui-mobile/src/forge/adventure/data/DialogData.java`** — Skip Tutorial (2026-08-11,
  round 6): added `ActionData.runCommand` (`String`, default null) - when set, the dialog action
  runs it through `ConsoleCommandInterpreter`, reusing that class's existing command set (e.g.
  `"teleport to poi Spawn"`) from inside a quest dialog action instead of only from the debug
  console or an item's `commandOnUse`. Deliberately generic rather than a single-purpose
  `teleportToPOI` field - see the field's own comment.
- **`forge-gui-mobile/src/forge/adventure/util/MapDialog.java`** — Skip Tutorial (2026-08-11,
  round 6): `setEffects()` gained one new branch, `if (E.runCommand != null && !E.runCommand.
  isEmpty()) ConsoleCommandInterpreter.getInstance().command(E.runCommand);`, mirroring exactly
  how `InventoryScene.java` already dispatches an item's `commandOnUse` - same interpreter
  singleton, same call shape, just reachable from `DialogData.ActionData` too now.

### 2026-08-12 QC round deltas (files above already have entries; this indexes the day's edits)
One round: first Progressive Set Unlocks playtest fixes + a Fable deep-dive review of the prior
4 days (full detail in `MOD_CHANGELOG.md`'s 2026-08-12 entry). Per-file deltas:
- **`scene/SpellSmithScene.java`** — **first-ever edit to this stock scene** (no prior entry):
  `enter()` populates the edition dropdown from a new `visibleEditions()` filter, and
  `filterResults()` restricts the card pool to `unlockedEditions` — both no-ops unless
  `editionProgressionEnabled` (#4), so stock planes see the unfiltered stock behavior.
- **`data/RewardData.java`** — `cardPackShop` case: empty-`allEditions` guard (skip + stderr)
  instead of `nextInt(0)` crash when an edition restriction leaves no booster-capable edition.
- **`data/ConfigData.java`** — 3 new opt-in flags: `armoryGuardsEnabled`, `shopTypeRerollEnabled`,
  `arenaUpgradesEnabled` (review found those features reaching stock planes ungated).
- **`scene/RewardScene.java`** — the three shop-regeneration paths route through
  `EditionProgression.restrictShopRewardsForCurrentTown()`; Armory/shop-type-re-roll buttons now
  behind the new flags; booster-shop "go research" note + Refresh refusal (before charging) when
  no unlocked edition can make a booster.
- **`scene/ArenaScene.java`** — `refreshArenaBuildingButtons()` hides all upgrade-economy buttons
  unless `arenaUpgradesEnabled`.
- **`scene/WorldStandingsScene.java`** — reputation label tint WHITE-when-markup (BLACK tint was
  multiplying the tier colors away).
- **`character/EnemySprite.java`** — overworld fog gate now requires `isExploredWorld()` AND
  `isCurrentlyVisible()` (owned-but-unexplored ground renders black yet counted as visible).
- **`stage/WorldStage.java`** — `ResourceSpawnActor.draw()` gained an `isExploredWorld()` fog
  gate (was entirely ungated), tile coords via `getTileSize()`.
- **`stage/MapStage.java`** — inline shop edition-restriction block extracted to
  `EditionProgression.restrictShopRewardsForCurrentTown()` (behavior-identical).
- **`world/World.java`** — `isCurrentlyVisible()` uses the difficulty-scaled radius (was reading
  the raw baseline field), cached per frame (`cachedVisionRadius` in `setPlayerTilePosition()`);
  `isTemporarilyRevealed()` `isEmpty()` fast path.
- **`util/TownRestoration.java`** — Capitol upgrade routes a built Armory onto the reserved
  Capitol Armory slot (new `readCapitolArmorySlotId()`), migrates hired guards, and memoizes
  `readMapObjects()` per mapPath. Same-day follow-up: armory detection now via the shared
  `EconomyBuildings.isArmoryShopName()` (the "EquipmentL2" L2-variant escape), excludes every
  armory-named rebuilt shop, and `repairCapitolState()` strips armory pins off regular slots.
- **`util/EconomyBuildings.java`** — new `isArmoryShopName(String)` single shared armory-family
  matcher (strips the "L2" suffix); `isArmoryShop(ShopData)` now delegates to it.
- **`scene/WorldStandingsScene.java`** (also above) — info dialogs rebuilt with wrapped,
  width-capped labels via a local `showInfoDialog()` (unwrapped long text overflowed the stage
  and soft-locked the scene).
- **`data/RewardData.java`** (also above) — Union branch's allCardVariants re-fetch now preserves
  the pool pick's edition (`getCardByNameAndEdition`), matching `CardUtil.generateCards()`.
- **`util/EditionProgression.java`** (mod-added, inventoried below) — deep-clones `cardUnion` in
  `restrictToEditions()`; new `restrictShopRewardsForCurrentTown()` +
  `playerHasBoosterCapableUnlockedEdition()`.
- **`scene/ResearchScene.java`** (mod-added, inventoried below) — scroll focus, filter toggles,
  researched-only view, `clearSelectable()` per rebuild, `switchToLast()` exit.

### 2026-08-12 Content Filter Tables (see MOD_CHANGELOG's entry for the design)
- **`util/ContentFilterTables.java`** — NEW mod file (inventoried below): CSV generate/merge/read
  + exclusion lookups for the three user-editable content tables.
- **`util/Config.java`** — `loadResources()` opens with
  `ContentFilterTables.applyEditionExclusions(configData)` (folds table exclusions into
  `restrictedEditions` before the token filter and card-pool init).
- **`data/ConfigData.java`** — new opt-in flag `contentFilterTablesEnabled`.
- **`data/ItemListData.java`** — static loader hands the freshly-loaded list to
  `ContentFilterTables.filterItems()` (quest items protected).
- **`data/WorldData.java`** — `getAllEnemies()` registers the catalog with
  `ContentFilterTables.registerEnemies()` (registration only - catalog deliberately unfiltered).
- **`data/BiomeData.java`** — `getEnemyList()` skips excluded enemies (roaming pool only).
- **`stage/MapStage.java`** — tmx enemy case skips excluded ORDINARY (non-boss, non-quest)
  dungeon population.
- **`scene/ArenaScene.java`** — `loadArenaData()` pre-filters the enemy pool with an
  empty-pool fallback (also removes an infinite-loop hazard when a pool name can't resolve).

### 2026-08-12 race expansions + Inn tournament lock
- **`data/AdventureEventData.java`** — first mod edit to this stock file: both event-pool pickers
  (`pickWeightedCardBlock`, `pickJumpstartCardBlock`) now apply
  `EditionProgression.eventAllowedEditionCodes()` (null = no restriction, so stock planes and
  pre-feature saves are untouched).
- **`data/HeroListData.java`** — first mod edit: new `getRawRaceName(int)` accessor (raw
  heroes.json name; `getRaces()` returns localized labels).
- **`player/AdventurePlayer.java`** — `create()`'s starting-unlock seeding is race-driven with
  random pick-N (see MOD_CHANGELOG); added `forge.util.MyRandom` import.
- **`data/ConfigData.java`** — new `raceEditions` array field (`RaceEditionData[]`).
- **`data/RaceEditionData.java`** — NEW mod file (race -> 4 edition codes).

### 2026-08-12 multi-resource cost overhaul
- **`data/DialogData.java`** — `ActionData` gained `addWood`/`addStone` (+copy-ctor lines).
- **`util/MapDialog.java`** — `setEffects()` handles the two new fields.
- **`character/OnCollide.java`** — new `withRebuildCost()` (per-building rebuild price for gated
  non-shop buildings; Arena uses it via MapStage's arena case).
- **`util/EconomyBuildings.java`** — multi-resource cost core (`costLabel`/`canAffordCost`/
  `payCost`/`spendCostAction`), per-type `buildCostFor()`, per-shop-type repair costs; flat
  BUILD_COST/BUILDING_UPGRADE_COST retired (ARMORY_UPGRADE_STONE/ARENA_UPGRADE_* replace them).
- **`util/TownRestoration.java`** — restore/rebuild/Capitol costs re-priced (multi-resource),
  custom-cost `buildRebuildShopDialog` overload; dead gold-only helpers removed.
- **`scene/RewardScene.java` / `scene/ArenaScene.java` / `scene/ResearchScene.java`** — upgrade/
  research buttons re-priced (stone/wood/shards) with glyph labels.
- **Plane assets** (not engine, listed for completeness): `The Forsaken Realms/sprites/
  items.png/.atlas` gained "Wood"/"Stone" 16x16 regions so [+Wood]/[+Stone] font tags resolve.

### 2026-08-12 dynamic armory item pools
- **`data/RewardData.java`** — new `itemRarity` field (+copy-ctor); the "item" reward case
  expands it to the full catalog-by-rarity pool when no explicit itemNames list is given.
- **`data/ItemListData.java`** — new `getItemNamesByRarity()` (excludes quest items and
  Landscape Sketchbooks; reads the live, content-filter-aware list).

### 2026-08-12 day/night terrain life modifier
- **`world/World.java`** — new `applyDayNightTerrainLife()`; `NIGHT_START_HOUR` 20 -> 18
  (day/night now 6am-6pm / 6pm-6am per user spec; isNight() had no consumers before this).
- **`scene/DuelScene.java`** — the enemy starting-life line routes through the modifier for
  overworld fights only (`eventData == null && !MapStage.isInMap()`); added MapStage import.

### 2026-08-12 Armory weighted-rarity mix
- **`data/RewardData.java`** — new `rollWeightedItemRarity()` (60/30/8/2 cumulative) and a
  `"Weighted"` sentinel branch in the "item" reward case (per-slot independent rarity roll,
  no-duplicate-within-roll preserved).
- **`util/ContentFilterTables.java`** (mod file, inventoried below) — items.csv gained a Notes
  column flagging 23 confirmed-unreachable items "Currently Unused" (informational, not excluded).

### 2026-08-13 Guard payment priority + Bank preferences
- **`player/AdventurePlayer.java`** — two new persisted booleans, `payGuardsFromBankFirst` /
  `goldMineDepositsToBankDirectly` (both default true; `clear()`/`save()`/`load()` updated, old
  saves default true via an inverted containsKey guard).
- **`util/EconomyBuildings.java`** (mod file, inventoried above) — guard-salary payment split into
  its own pass over towns sorted Capitol-first-then-nearest (`townsByCapitolPriority()`), split
  per-guard between that town's bank and the player's inventory (`payGuardGold()`); Gold Mine
  production redirects to the town's bank when it has one and the checkbox is set; two new
  CheckBoxes added to `refreshBankDialog()` (first Dialog-embedded CheckBox in the mod). See
  MOD_CHANGELOG.md.

### 2026-08-13 Capitol FoW Stage-3 reveal fix + Bank dialog compaction
- **`util/TownRestoration.java`** (mod file, inventoried below) — new `applyCapitolVisionReveal()`
  helper (one-time `revealArea()` + `refreshFogInRadius()` over the Capitol's
  `getTownVisionRadiusTiles()` circle), called from `upgradeToCapitol()` (new upgrades) and
  `repairCapitolState()` (self-heals existing saves on every load). See MOD_CHANGELOG.md.
- **`util/EconomyBuildings.java`** (mod file, inventoried below) — `refreshBankDialog()`'s 4
  Deposit/Withdraw buttons switched from `addButtonRow()` (full-width) to `addHalfButton()`
  (half-width, 2 per row) to stop the dialog running taller than the screen. See MOD_CHANGELOG.md.

### 2026-08-13 Capitol land-shop ruins, Torch item, resource-pickup sparkle for all 5 types
- **`util/TownRestoration.java`** (mod file, inventoried below) — new `LAND_SHOP_RUIN_REGIONS`/
  `getLandShopRuinSprite()`, called from `getBrokenShopSprite()` before its existing generic-pool
  fallback - the Capitol's 6 fixed land shops (ids 55/77/78/79/80/81) now get their own
  color-matched 16x16 ruin instead of a random pick from the shared 64-variant pool. Guarded on
  `isCurrentTownCapitol()` specifically to avoid the exact objectId-collision bug class the
  existing generic picker's own comment already documents (shared .tmx templates reuse raw ids
  across unrelated towns).
- **`data/EffectData.java`** — **first-ever mod edit to this file.** New `visionRadiusMultiplier`
  field (default 1.0f, same "Map only" effect category as the pre-existing `moveSpeed`), plus a
  `getDescription()` line for it, following that field's exact pattern. Backs the new Torch item.
- **`player/AdventurePlayer.java`** — new `visionRadiusMultiplier()`, exact structural copy of the
  pre-existing `equipmentSpeed()`/`goldModifier()` equipped-item-effect-product pattern.
- **`world/World.java`** — `getVisionRadius()` now multiplies in
  `Current.player().visionRadiusMultiplier()` - the exact extension point `visionRadius`'s own
  field comment already flagged ("items will raise this later").
- **`util/Paths.java`** — added `WOOD_ATLAS`/`STONE_ATLAS`/`SHARDS_ATLAS`/`MYSTERY_ATLAS` (new
  plane-scoped sparkle atlases, see `WorldStage.java` below). Pre-existing `GOLD_ATLAS`'s VALUE is
  unchanged, but the plane now has its own `sprites/gold.atlas` (pointing at the user's new
  `resource_drop.png` sheet, not the stock `treasure.png`) that shadows the stock one via the
  ordinary plane-first file resolution every other plane-scoped asset already uses - no code
  change needed for Gold's art to switch over.
- **`stage/WorldStage.java`** — `goldSparkleAnimation`/`getGoldSparkleAnimation()` generalized to a
  `Map<Integer, Animation>` cache + `getSparkleAnimation(int type)` keyed by `ResourceSpawns.TYPE_*`;
  `refreshResourceSpawnActors()` now calls it unconditionally instead of gating on `isGold`. All 5
  resource-pickup types get the real sparkle animation now; the alpha-twinkle fallback in
  `ResourceSpawnActor.draw()` is untouched (kept as a defensive fallback if an atlas ever fails to
  load, not expected in practice).

### 2026-08-13 (later) Playtest round: FoW gap, Armory L2 refresh, trophy items
- **`world/WorldSave.java`** — `load()` gained a call to `TownRestoration.
  repairAllTownVisionReveal(world)` right after `repairCapitolState()`, self-healing every
  restored town's fog-of-war vision-circle reveal (not just the Capitol's) on every load.
- **`scene/RewardScene.java`** — `promptUpgradeArmory()` now resolves the shop's L2-suffixed
  `ShopData` (via `WorldData.getShopList()`) BEFORE charging/flipping the level flag (no-charge-
  no-change if no L2 entry exists - a real pre-existing shops.json gap for 5 AI-capital colored
  armory shops), then swaps it onto the actor and regenerates+redraws the reward grid immediately
  instead of only on the next full map rebuild.
- **`data/ItemData.java`** — new `excludeFromGeneralSale` boolean field (+ copy-constructor line).
- **`data/ItemListData.java`** — `getItemNamesByRarity()`'s skip condition gained
  `|| item.excludeFromGeneralSale` alongside the existing `questItem` check.
- **`util/TerritoryControl.java`** / **`util/TownRestoration.java`** (both mod files, already
  inventoried below - listed here anyway per this file's own per-round convention) —
  `TownRestoration.applyCapitolVisionReveal()` generalized to package-private
  `applyTownVisionReveal(world, poi, changes)` (works for any restored town, not just the
  Capitol); new `TownRestoration.repairAllTownVisionReveal(world)`;
  `TerritoryControl.processTerritoryExpansion()`'s player-town growth block now calls the shared
  helper instead of a raw `revealArea(..., newTownRadius, ...)`, fixing the reveal radius to
  match the actual (Outlook-aware) vision circle.
- **`util/EconomyBuildings.java`** (mod file, inventoried below) — guard-salary payday moved from
  a per-guard rolling timer to a shared calendar schedule (`nextPayday` formula in
  `processDaysPassed()`); `NON_MYTHIC_ITEM_POOL` lost 3 trophy-item names (see MOD_CHANGELOG.md).

### 2026-08-13 (later still) AI-town gate + diagnostic logging
- **`scene/RewardScene.java`** — Armory-family button visibility (`guardsButton`/`upgradeButton`/
  `rerollButton`/`shopTypeRerollButton`) and their 4 click handlers now gated on
  `TownRestoration.isCurrentTownPlayerOwned(changes)` — previously the only economy-building
  action path reachable at AI-owned towns (Re-roll Inventory/Re-roll Shop Type were live and
  functional there; Upgrade Armory only failed by an unrelated data gap).
- **`util/TownRestoration.java`** (mod file, inventoried below) — new
  `isCurrentTownPlayerOwned(PointOfInterestChanges)` helper.
- **`util/TerritoryControl.java`** (mod file, inventoried below) — `maxActiveMagesPerColor()`
  gained `[TFR-MageCap]`; `dispatch()` gained `[TFR-Targeting]` (captures the roll into a new
  `originalRoll` local before the existing roll-consuming loop, no behavior change).
- **`stage/WorldStage.java`** — `handleMonsterSpawn()`'s existing `[TFR-Spawn]` line extended
  with `speed=`/`life=` fields.
- **`scene/DuelScene.java`** — new unconditional `[TFR-EnemyLife]` line after the difficulty-scaled
  starting-life computation (raw life, difficulty factor, scaled result, terrain-adjusted result
  if different).

### 2026-08-13 (evening) FoW real root cause, button greyout, Torch redesign, Deck Tester AI-vs-AI
- **`util/TerritoryControl.java`** / **`util/TownRestoration.java`** (both mod files, already
  inventoried below) — Capitol daily-territory-expansion block's own `revealArea()`/
  `refreshFogInRadius()` re-added (removed 2026-08-11, superseded by today's changed user spec),
  gated on the radius having actually grown that tick (adversarial-review perf fix);
  `repairAllTownVisionReveal()` gained a Capitol-specific extra sweep of the live territory
  radius.
- **`scene/RewardScene.java`** — `upgradeButton`/`shopTypeRerollButton` gained the missing
  `.setDisabled(...)` calls that every other cost-gated button already had.
- **`character/ShopActor.java`** — `onPlayerCollide()`'s default case gained the guaranteed-
  first-Armory-Torch grant (`AdventurePlayer.addItem("Torch")`, characterFlags-gated).
- **`data/RewardData.java`** — a same-day generation-time forcing approach for the Torch feature
  above was implemented then fully reverted after adversarial review found it blocking (see
  MOD_CHANGELOG.md); net change to this file today is zero (confirmed via `git status`).
- **`scene/DuelScene.java`** — new `initDuels(..., boolean aiControlsPlayerSide)` overload;
  `enter()`'s player-seat construction branches to `GamePlayerUtil.createAiPlayer(...)` when set;
  `GameEnd()`'s pre-existing mana-shard-persistence line gained a null-check (a fully-simulated
  match's spectator controller has no `Player`, previously an every-time caught-but-logged NPE).
- **`scene/ArenaScene.java`** — Deck Tester flow restructured with a mode-choice dialog ahead of
  the existing deck pickers; new `launchDeckTesterSimulated()`.

### 2026-08-13 (late night) DuelScene race fix, Temple-icon root cause, Progressive Set Unlocks bugs, Mysterious Mage, edition-restriction logging
- **`scene/DuelScene.java`** — `GameEnd()` gained a second null-guard, this time around
  `hostedMatch.getGame()` itself (a real race against stock `HostedMatch.endCurrentGame()`,
  confirmed via 2 stack traces in the user's own forge.log) — logs `[TFR-DuelEndRace]` when it
  fires, `winner` defaults false rather than aborting the whole persistence block.
- **`world/World.java`** — new private `mapMarkerKey(PointOfInterestData)` helper, called from both
  minimap-marker-drawing sites (`generateNew()`'s POI-placement loop, `redrawAllPoiMarkers()`) —
  Story-tagged `type="castle"` POIs (excluding the 5 "Boss"-tagged Chapter-1 castles) resolve to
  the "dungeon" marker instead of "castle" (Temple-icon collision root cause — see
  MOD_CHANGELOG.md).
- **`character/EnemySprite.java`** — the existing edition-restriction loot check gained an
  `EXEMPT`-logging else-branch for the pre-existing boss/quest-tagged skip (was silent before).
- **`data/RewardData.java`** — `cardPackShop` case's `colors!=null` branch now passes `this.editions`
  through to `AdventureEventController.generateBoosterByColor()`'s new restricted overload instead
  of the old always-unrestricted call (colored-booster edition-restriction bypass fix).
- **`data/AdventureEventData.java`** — new private `logInnEditions()` helper; `pickWeightedCardBlock()`
  gained a `formatForLogging` parameter (still routes to the same restriction logic, just labeled
  for the new log line); both it and `pickJumpstartCardBlock()` now log the new `[TFR-InnEditions]`
  tag.
- **`data/DialogData.java`** — `ActionData` gained a new `String refreshShopRewardsTrigger` field
  (null = off; non-null = trigger label consumed by `MapStage.refreshAllShopRewards(String)`),
  wired into the copy constructor.
- **`util/MapDialog.java`** — action-execution loop gained one new branch dispatching
  `refreshShopRewardsTrigger` to `stage.refreshAllShopRewards(trigger)`.
- **`util/AdventureEventController.java`** — new `generateBoosterByColor(String, String[])` overload
  building its own `BoosterPack`/`SealedTemplate` with a `fromSets("...")` predicate clause per slot
  (a pre-existing stock `BoosterGenerator` operator) when an edition restriction is supplied; the
  old single-arg method now delegates to it with `null`.
- **`scene/RewardScene.java`** — its 4 existing `EditionProgression.restrictShopRewardsForCurrentTown()`
  call sites (`promptRerollShopType`/`promptUpgradeArmory`/`promptRerollArmory`/`restockShop`) each
  now pass an explicit trigger label instead of relying on a removed default.
- **`stage/MapStage.java`** — its existing `restrictShopRewardsForCurrentTown()` call site (initial
  shop-build) now passes `trigger="init"`; new public `refreshAllShopRewards(String trigger)`
  method (re-derives every `ShopActor`'s rewards in the current town from its existing seed) — see
  MOD_SCOPE.md #55 for why.
- **`util/EconomyBuildings.java`** / **`util/TownRestoration.java`** / **`util/EditionProgression.java`**
  (all 3 mod files, already inventoried below) — `EconomyBuildings.buildOption(NONE,...)` and
  `buildSimpleRepairDialog()`, and `TownRestoration.buildRestoreTownDialog()`/`buildRebuildShopDialog()`,
  each now set `refreshShopRewardsTrigger` on their "yes"/"repair" dialog action (stale
  edition-restriction bake-in fix); `EditionProgression.restrictShopRewardsForCurrentTown()` gained
  a `trigger` parameter (the old 3-arg overload was removed, all 6 call sites updated) plus a
  `reason` field and the town name on its `[TFR-ShopEditions]` log line.

### 2026-08-13 Deck Tester 50x speed, "AI vs. AI - No Watch" headless batch mode, mode rename
- **`gui/control/PlaybackSpeed.java`** (`forge-gui`, shared/global, NOT under `forge/adventure/`) —
  new `SUPERFAST(.02)` enum constant inserted into the existing 3-way speed cycle
  (`NORMAL->FAST->SLOW->NORMAL` → `NORMAL->FAST->SUPERFAST->SLOW->NORMAL`), labeled "50x speed".
  Affects every spectated/AI-vs-AI match in Forge (this control has no per-caller scoping), not just
  Adventure's Deck Tester — user-requested, low-risk (a strictly-faster tier added to an existing
  cycle, no other behavior changed).
- **`toolbox/FCardPanel.java`** (`forge-gui-mobile`, shared UI, NOT under `forge/adventure/`) — a
  pre-existing `== PlaybackSpeed.FAST` animation-skip check extended to also match `SUPERFAST`
  (adversarial-review-adjacent self-catch, not a review finding — the fastest tier needs to skip at
  least as much as FAST, not silently re-enable animation).
- **`scene/ArenaScene.java`** (mod file) — Deck Tester's `boolean simulated` parameter became a
  3-value `DeckTesterMode` enum; new `promptMatchCount()` and `launchDeckTesterBatch()` methods for
  the new "AI vs. AI - No Watch" flow; `promptDeckTester()` gained a `|| !enable` guard.
- **`util/DeckTesterSimulator.java`** (new mod file) — headless AI-vs-AI batch runner, drives
  forge-game's `Match`/`Game` engine directly on a background thread, bypassing
  `HostedMatch`/`MatchController`/`DuelScene` entirely. See MOD_CHANGELOG.md for the adversarial-
  review-caught blocking exception-handling fix.

### 2026-08-13 (later still) Deck Tester real freeze fix, Arena ownership gate, dungeon-loot edition gap, edition status command
- **`util/DeckTesterSimulator.java`** (new mod file) — the earlier round's adversarial-review fix
  only guarded against exceptions; a genuine hang (`Match.createGame()` called synchronously on the
  batch thread, outside the timeout-protected executor) could still freeze the whole batch forever,
  which is what the user hit. `createGame()` moved inside the same per-game executor `startGame()`
  already used; the single blocking `future.get(90s)` replaced with a 500ms poll loop against the
  real deadline. `runBatch()` signature changed `void` → new `DeckTesterSimulator.Handle`
  (an `AtomicBoolean` cancel flag) for the new "End Test" button.
- **`scene/ArenaScene.java`** (mod file) — `launchDeckTesterBatch()` captures the new `Handle` and
  wires an "End Test" button into the progress dialog. `refreshArenaBuildingButtons()`/
  `promptUpgradeArena()` gained a `TownRestoration.isCurrentTownPlayerOwned(...)` check (nested
  inside the pre-existing `arenaUpgradesEnabled` flag, so inert wherever that flag is already off) —
  previously the 5 AI-color capitals showed the same Upgrade-to-Level-2 button as the player's own
  Capitol, same exploit shape the Armory buttons were fixed for on 2026-08-13 (see that entry above).
- **`util/TerritoryControl.java`** (new mod file) — `currentColorAtPoi()` changed `private` → `public`
  (zero behavior change), so `EditionProgression` can reuse it for dungeon-loot restriction below.
- **`util/EditionProgression.java`** (new mod file) — new `restrictDungeonRewardsForCurrentPoi()`,
  keyed off `TerritoryControl.currentColorAtPoi()`; early-returns the source unchanged when
  `editionProgressionEnabled` is off, same fail-open contract as the rest of this class.
- **`character/RewardSprite.java`** (mod file) — `getRewards()` (dungeon treasure/chest pickups) now
  routes through `EditionProgression.restrictDungeonRewardsForCurrentPoi()` before generating -
  previously drew from every edition unconditionally, unlike roaming-monster loot and AI-town shops
  (real gap, found by a background QC-design pass, not a user report).
- **`util/ResourceSpawns.java`** (new mod file) — `checkPickup()` switched from an exact-tile-
  equality check on the player sprite's raw (corner) `getX()/getY()` to a real distance check from
  the sprite's center (matching how `WorldStage`'s nav-arrow already computes it) to the spawn
  tile's center, plus a small added tolerance (`PICKUP_RADIUS_TILES = 0.75`). User report: "I feel
  like I run over it a few times before it picks up" — the corner-vs-center gap was the actual bug.
- **`stage/ConsoleCommandInterpreter.java`** (mod file) — new `edition status` command: dumps every
  color's shard, the player's unlocked editions, and (if standing at a PoI) that PoI's current
  territory color - a QC-design-agent proposal, on-demand alternative to grepping `forge.log` for
  the right `[TFR-ShopEditions]`/`[TFR-LootEditions]` line.

### 2026-08-13 (late night 2) Grandmaster rename, tiered enemy names, holistic-review fixes
- **`data/EnemyData.java`** / **`data/ConfigData.java`** / **`character/EnemySprite.java`** (mod
  files) — new `tierDisplayName()`/`getTieredDisplayName()` + `showEnemyTierInName` flag.
- **`scene/DuelScene.java`** — tiered opponent nameplate/boss-dialog titles (raw for event duels);
  Deck Tester matches no longer write duel statistics (`fixedDeck` discriminator).
- **`stage/WorldStage.java`** — vs-screen names tiered; roaming-enemy save now stores the raw
  `name` field instead of `getName()` (pre-existing nameOverride save/load collision fix).
- **`stage/MapStage.java`** / **`scene/ArenaScene.java`** / **`scene/WorldStandingsScene.java`** /
  **`util/EconomyBuildings.java`** / **`util/TerritoryControl.java`** /
  **`pointofintrest/PointOfInterestChanges.java`** — display-site swaps, guardTierDisplayName
  delegation, Grandmaster label text/comments.
- **`util/EditionProgression.java`** — dungeon-chest restriction fixes: authored `editions` themes
  pass through untouched; non-shard territory colors ("waste"/"player"/"ocean") now fall back to
  NEUTRAL instead of silently unrestricting.
- **`util/DeckTesterSimulator.java`** — End Test abort no longer tallied as a completed draw;
  abandoned games stopped via `setGameOver(Draw)` (stock SimulateMatch's own mechanism).
- **`util/Config.java`** — null-guard on `commanderDecks` in `starterDeck()`'s pre-existing
  Pile→Commander fall-through (armed by this plane's Commander-mode removal).
- **`stage/ConsoleCommandInterpreter.java`** — "edition status" gated on isInMap() (stale
  rootPoint from the overworld).
- **`toolbox/FCardPanel.java`** / **`gui/control/PlaybackSpeed.java`** (shared files, entries
  above) — comment date corrections only (08-14 → 08-13).

### 2026-08-13 (late night 3) FoW threshold fix, castle strength, printing remap, renames, icons, shops
- **`world/World.java`** — `claimWastelandRing()` gained an optional `outClaimedTiles` output
  param (old signature delegates with null); new `isLandTile()`/cached land-tile denominator for
  `checkFogOfWarStage2()`; new `revealPlayerOwnedTiles()`/`resetFogOfWarToOwnership()`; new
  `[TFR-FoW]` logging; `cachedLandTileTotal` reset added to both `load()` and `generateNew()`
  (adversarial-review fix); `redrawAllPoiMarkers()` special-cases the Capitol POI's own sprite.
- **`util/TerritoryControl.java`** — new `AI_CASTLE_PULL_WEIGHT`/`AI_CASTLE_EXCLUSION_RADIUS_TILES`
  constants, applied only to the 5 AI castle pull sources; Capitol daily-expansion block now
  advances its radius only when a ring claims something and reveals exactly the claimed tiles.
  An initial companion change (`rivalCastleKeepSkip()`, applying the castle exclusion to
  `repaintBiomeAroundTown()`) was added then fully reverted after adversarial review found it
  caused a permanent territory-radius/actual-paint desync near rival castles - see
  MOD_CHANGELOG.md for the full writeup.
- **`util/TownRestoration.java`** — load-time Capitol vision sweep uses the new
  `revealPlayerOwnedTiles()` instead of a blind radius-disc reveal.
- **`stage/WorldStage.java`** — the `[TFR-Intrusion]` roll moved below the `spawnDelay` gate (was
  running every frame); `load()` gained a null-guard for an unresolvable saved roaming-enemy name.
- **`util/CardUtil.java`** — new `remapToEditionList()`, called from `generateCards()` and
  `RewardData`'s Union branch; logging deduped per (card, from, to) triple (adversarial-review
  fix).
- **`data/RewardData.java`** — Union branch (both `allCardVariants` and plain paths) now calls
  `CardUtil.remapToEditionList()` before generating the final card.
- **`util/EconomyBuildings.java`** — the 3 Challenge Coin item names removed from
  `NON_MYTHIC_ITEM_POOL` (the Archaeologist expedition table).
- **`stage/ConsoleCommandInterpreter.java`** — `give wood`/`give stone` now play
  `SoundEffectType.CoinsDrop` + log `[TFR-Give]`; new `fog reset` command.

### 2026-08-14 Territory pacing split, Guard Info dialog, dialog text-wrap fix, Spellsmith editions
- **`util/MapDialog.java`** — shared `DialogData` option-button renderer now prefixes every button
  label with `[%88]` (text-wrap fix, see MOD_CHANGELOG.md).
- **`util/TerritoryControl.java`** (mod file, inventoried below) — `EXPANSION_TILES_PER_DAY` split
  into 3 rates (AI castles unchanged at 9/day, new `CAPITOL_EXPANSION_TILES_PER_DAY = 1`, ordinary
  towns moved to a day-tracked 1-tile/7-days mechanism via `World.townLastGrowthDay`). Also:
  `dispatch()`'s hardcoded "Adept `<Color>` Wizard" replaced with a weighted tier roll
  (`rollDispatchMageTier()`) + a new `pickGrandmasterMage()` (that color's own Mythic-tier roaming
  pool, since no color has a named Grandmaster wizard).
- **`world/World.java`** — new `townLastGrowthDay` field (`Map<String, Integer>`, get/set/save/
  load/NG+-clear(), same pattern as `townTerritoryRadius` immediately above it).
- **`util/EconomyBuildings.java`** (mod file, inventoried below) — new `buildGuardInfoDialog()` +
  "Info" button on the Manage Guards dialog.
- **`util/ColorReputation.java`** — new `isSpellsmithAccessible(color)` (Happy/Partner only).
- **`scene/SpellSmithScene.java`** — edition filter now branches player-owned-town vs. AI-color-town
  (via `EditionProgression.getEditionsForColor()`) instead of always using the player's own
  unlockedEditions.
- **`stage/MapStage.java`** — "spellsmith" collision case gated on `isSpellsmithAccessible()`,
  showing a blocking dialog below Happy/Partner standing instead of opening the scene.

### Trivial / non-gameplay
- **`.gitignore`** — stopped ignoring `.claude/skills/` specifically so project skills travel with
  the repo, while still ignoring the rest of `.claude/`. Not engine code, listed for completeness.

### 2026-08-14 (later) Color Defeat endgame consequence, colorless spawn mix-in, 8 dead enemy refs
- **`util/TerritoryControl.java`** (mod file, inventoried below) — new `defeatColor(World, String)`
  (the whole endgame consequence: full-map terrain/town revert to neutral via
  `neutralizeTerritoryOutsideRadius(color, castlePos, 0, ...)` + per-POI `transformInto()`,
  `regenerateDoodadsForBiome("waste")`, reputation penalty, forced-ally-targeting), new public
  `onCastleQuestFlagSet(String, int)` and `castleCompleteFlagName(String)`. `maxActiveMagesPerColor()`
  gained a `World` parameter (+1 cap per `World.getDefeatedColorCount()`) and `rollDispatchMageTier()`
  gained one too (tier-shift via new `dispatchTierCumulative(World)`) - every call site updated.
  `dispatch()`'s target-selection was restructured to hoist `candidates`/`weights`/`originalRoll`/
  `totalWeight` above the (now-conditional) weighted-pick block so the forced-player-targeting branch
  can bypass it while the existing `[TFR-Targeting]` diagnostic log below still compiles.
  `processDaysPassed()` and `buildPullSources()` both skip a color once `World.isColorDefeated()`.
- **`world/World.java`** — new `defeatedColors`/`forcedPlayerTargetPending` (`Set<String>`) with
  get/set/save (`storeObject`)/load (`readObject` + `containsKey` guard)/new-game-`.clear()`, same
  persistence pattern as `colorNextAttackDay` immediately above them.
- **`util/ColorReputation.java`** — new `applyColorDefeatPenalty(color)`: flat -50
  (`DEFEAT_PENALTY_HALF_POINTS = -100`), deliberately NOT zero-sum (the class's own net-zero
  invariant is documented as being for duel events specifically).
- **`stage/MapStage.java`** — `setQuestFlag()` now also calls `TerritoryControl.onCastleQuestFlagSet()`
  (the one real call site every castle's boss-defeat dialog action fires).
- **`stage/ConsoleCommandInterpreter.java`** — new `defeat castle <color>` command, **testing-only,
  marked for removal** once the feature is playtested (user request 2026-08-14) - replicates the
  real flag-write + quest notification, then calls `defeatColor()` directly.
- **`stage/WorldStage.java`** — new `PLAYER_COLORLESS_MIX_CHANCE` (8%): `handleMonsterSpawn()` can
  now substitute the colorless/Wasteland roster for a roll on the player's own biome, independent of
  the existing foreign-color intrusion mechanism.

### 2026-08-14 (even later) Adversarial-review fixes, in-flight-mage fix, SpellSmith/Arena/Torch/Armory/Guard-dialog/shop-reroll round
- **`util/TerritoryControl.java`** (mod file, inventoried above) — 9 fixes from adversarial review
  (`onCastleQuestFlagSet()`'s real hook moved OUT of this file entirely, to `player/AdventurePlayer.
  java` - see below; `repairMissingCapitals()`/`processTerritoryExpansion()` both gained
  `isColorDefeated()` skips; `defeatColor()` no longer calls `regenerateDoodadsForBiome()`, clears
  its own `forcedPlayerTargetPending` entry, and its ally-arming comment corrected; `dispatch()`'s
  forced-target Capitol add now deduped; `dispatchTierCumulative()`'s `grandmasterShare` no longer
  dead code; `findNearbyForeignColor()` gained an `isColorDefeated()` check). Separately, a real
  playtest found ANOTHER bug the review missed: `onMageArrived()` now checks `isColorDefeated()`
  first (before even the Capitol-defense branch) so an already-in-flight mage from a just-defeated
  color can't still capture a town or trigger the Capitol's forced duel.
- **`player/AdventurePlayer.java`** — `setQuestFlag()` now calls `TerritoryControl.
  onCastleQuestFlagSet()` (the REAL call site the boss-defeat dialog action fires, via
  `Current.player().setQuestFlag()` - `stage/MapStage.java`'s identically-named but unrelated
  method was the original, incorrect hook, reverted below).
- **`stage/MapStage.java`** — the incorrect `onCastleQuestFlagSet()` hook removed from
  `setQuestFlag()` (see above). Separately: `rerollShopType()` now requires
  `EconomyBuildings.isBoosterShop(candidateData) == currentIsBooster` (Booster/regular shops kept
  separate on reroll, MOD_SCOPE.md #32); both `refreshAllShopRewards()` and the initial-load shop
  generation now call `EconomyBuildings.injectGuaranteedTorchIfOwed()`.
- **`stage/ConsoleCommandInterpreter.java`** — `defeat castle <color>` now calls `Current.player().
  setQuestFlag()` directly (the real path) instead of manually replicating the old, wrong one.
- **`scene/SpellSmithScene.java`** — new shared `currentEditionRestriction()` helper; both
  `visibleEditions()` (previously unbranched - the actual bug) and `filterResults()` now call it.
- **`scene/RewardScene.java`** — `promptRerollShopType()`/`promptUpgradeArmory()`/
  `promptRerollArmory()` all now call `EconomyBuildings.injectGuaranteedTorchIfOwed()`; the
  `BuyButton` click listener now marks the Torch guarantee fulfilled on purchase.
- **`character/ShopActor.java`** — the old direct-to-inventory Torch grant removed from
  `onPlayerCollide()`'s default case entirely (unused `AdventurePlayer`/`GameHUD` imports removed
  too - caught by checkstyle).
- **`util/EconomyBuildings.java`** — new `injectGuaranteedTorchIfOwed()`; Guards dialog's Info/Close
  buttons now `addHalfButton()`-paired instead of full-width-stacked; new `addTableRow()` +
  `buildGuardInfoDialog()`'s content now wrapped in a `ScrollPane`.
- **`util/TownRestoration.java`** — `buildRebuildShopDialog()`'s body text no longer repeats the
  cost (Arena/Spellsmith/Shard Trader all share this template).
- **`util/AdventureQuestController.java`** (first tracked edit here, 2026-08-18, MOD_SCOPE.md
  #79) — `updateQuestsWin(EnemySprite, ArrayList<EnemySprite>)` now calls `DungeonRotation.
  onDungeonClear(TileMapScene.instance().rootPoint)` right after it computes its own
  `allEnemiesCleared` boolean for quest "Clear" objectives, nested inside the existing
  `enemies != null` branch specifically (the single-enemy overworld-duel overload of this same
  method passes `enemies=null`, and a bare `allEnemiesCleared` check would misfire on every
  ordinary overworld win since that boolean defaults `true` with no list to check against).
- **`util/TownRestoration.java`** — reputation bonus for upgrading a town to the Capitol
  (`upgradeToCapitol()`, see the #13/#1 entries above) raised from +1 to +2 (2026-08-18, user
  spec) - `newChanges.addMapReputation(oldChanges.getMapReputation() + 2)`, was `+ 1`.

### Shared (non-plane-scoped) card data
- **`forge-gui/res/adventure/common/custom_cards/tibalt_boss_effect.txt`** (2026-08-22) — real
  bug fix, not a mod feature: the 11-15 D20 result was chaining `DBDamageBis` into `DBChangeZone`
  (a random-graveyard-creature reanimation onto the caster's own battlefield) before `DBCleanup`,
  a silent bonus effect the card's own Oracle text never mentions ("Tibalt deals seven damage to a
  creature chosen at random" - nothing about reanimating anything). Now chains straight to
  `DBCleanup`, so 11-15 only deals damage, matching the documented text. Genuinely shared (custom
  card scripts load into the global card database, not per-plane) - no plane-scoped alternative
  exists, per `CLAUDE.md`'s exception clause. Found by comparing against a fix on an external fork
  (`github.com/tchntm43/forge`, "mods" branch) the user asked to review; confirmed as a real
  behavioral bug rather than a stylistic diff before applying.
  **Follow-up (2026-08-22, post-v1.00 review):** the fix above only changed the SubAbility chain -
  `SVar:DBDamage`'s own `SpellDescription$` (the text `RollDiceEffect.makeFormatedDescription()`
  actually shows the player in the boss-fight roll tooltip) still promised "Then Tibalt returns a
  random creature card to the battlefield." Removed that clause so the tooltip matches both the
  Oracle text (already correct) and the real, no-longer-reanimating behavior.

## New files (won't conflict with an upstream merge, but worth an inventory)

Under `forge-gui-mobile/src/forge/adventure/util/` - upstream doesn't have these paths, so there's
nothing to reconcile, but they're stock-adjacent code (not mod-plane assets) so they're listed here
rather than assumed-safe by omission:
`ColorReputation.java` (#1), `DungeonRotation.java` (#15, rotating dungeons/caves),
`QuestExpiry.java` (#16, side-quest timers),
`EconomyBuildings.java`, `ResourceDisplayActor.java`,
`ResourceSpawns.java` (random overworld resource pickups), `RubbleOverlay.java`,
`TerritoryControl.java`, `TimeOfDayActor.java`, `TownRestoration.java`,
`DeckTesterSimulator.java` (#20/#52, "AI vs. AI - No Watch" headless batch mode, 2026-08-13),
`EditionProgression.java` (#4, Progressive Set Unlocks - edition sharding + the clone-and-restrict
RewardData mechanism, 2026-08-12).
(`TownCountActor.java` existed briefly, removed the same day - see `MOD_CHANGELOG.md`'s "World
Standings page" entry.)

Under `forge-gui-mobile/src/forge/adventure/scene/`, same reasoning:
`WorldStandingsScene.java` (#7) - its own JSON layout lives in the mod's plane folder
(`The Forsaken Realms/ui/world_standings.json`), not `common/ui/`, so that part needs no tracking
here either - see "Everything else" below. `ResearchScene.java` (#4, 2026-08-12) - same reasoning,
its own layout lives at `The Forsaken Realms/ui/research.json`/`research_portrait.json`.

## Everything else (not tracked here - genuinely safe)

Every file under `forge-gui/res/adventure/The Forsaken Realms/` is mod-owned content (JSON
overrides, custom art, maps) - upstream Forge has no path collisions with that folder at all, so
none of it needs tracking here. See `MOD_SCOPE.md`/`MOD_CHANGELOG.md` for what's in it and why.

## Retroactive entries — ledger-gap audit, 2026-08-19 (found during the 2.0.15 upstream merge)

A file-by-file verification pass while merging upstream `forge-2.0.15` (commit `06019e99eed6`)
found these stock-engine edits that never got their entries when their rounds shipped (full
reasoning for each lives in `MOD_CHANGELOG.md`, rounds 10-17):

- **`forge-game/src/main/java/forge/game/Game.java`** (MOD_SCOPE.md #73) — new `rerollAnte()`
  method: returns every player's current ante to their library and re-picks via the existing
  `chooseCardsForAnte()`, honoring the match's ante-rarity/basic-land rules. Only invoked through
  the ante-reveal dialog's re-roll supplier; no stock code path calls it.
- **`forge-gui/src/main/java/forge/gui/interfaces/IGuiGame.java`** (#73) — new `revealAnteCards()`
  DEFAULT method delegating to plain `reveal()`, so every stock client behaves exactly as before;
  exists purely as an override point for the Adventure ante-re-roll UI.
- **`forge-gui/src/main/java/forge/gui/control/FControlGameEventHandler.java`** (#73) —
  `visit(GameEventAnteCardsSelected)` now routes through `getGui().revealAnteCards(...)`, passing a
  `Game.rerollAnte()` supplier.
- **`forge-gui-mobile/src/forge/screens/match/MatchController.java`** (#73-#75) —
  `revealAnteCards()` override: Adventure-plane-gated re-roll confirm dialog with escalating shard
  cost (`TuningData.anteRerollBaseShardCost`/`anteRerollEscalationRate` via
  `EconomyBuildings.scaledCost()`), post-choice affordability check, `[TFR-AnteReroll]` logging.
- **`forge-gui-mobile/src/forge/adventure/scene/EventScene.java`** (#75/#76) — Inn tournaments:
  Ante removed from all tournament matches; opt-in "simulate AI matches" checkbox (off by default,
  placed above the entry-fee button) runs real `Match`/`Game` AI-vs-AI resolution instead of the
  stock coin flip, `[TFR-InnAISim]` logging.
- **`forge-gui-mobile/src/forge/adventure/scene/NewGameScene.java`** (#70) — race-selection "?"
  help button: dialog listing each race's four starting expansions and how difficulty affects the
  number granted.
- **`forge-gui/src/main/java/forge/localinstance/properties/ForgeConstants.java`** (#76) — new
  `ADV_WORLDGEN_BG_FILE` constant (world-generation background image path).
- **`forge-gui-mobile/src/forge/assets/FSkinTexture.java`** (#76) — new `ADV_WORLDGEN_BG` entry
  backing the world-gen background.
- **`forge-gui-mobile/src/forge/screens/TransitionScreen.java`** (#76) — world-generation screen
  uses the user's `Main_Image.png` background; loading screens use the `Icon.png`-derived art.
- **`forge-gui-mobile-dev/src/forge/app/GameLauncher.java`** (#76) — running-window/taskbar icon:
  loads `res/skins/default/adv_icon_{256,128,64,32,16}.png` as absolute paths and calls
  `config.setWindowIcon()`; nothing set a window icon before.

New-files inventory additions (same reasoning as the "New files" section above):
`forge-gui-mobile/src/forge/adventure/scene/InfoTextScene.java` (#63, scrollable info-text scene
replacing broken oversized Dialogs; layouts live in the plane's `ui/info_text*.json`) and
`forge-gui-mobile/src/forge/adventure/data/TuningData.java` (#63/#74, numeric balance knobs loaded
from the plane's `config tables/settings.json`).

### 2026-09-06 Looted-dungeon despawn timer (round 128)

- **`forge-gui-mobile/src/forge/adventure/stage/MapStage.java`** — the private
  `clearDungeonIfEmptied()` (added 2026-08-30) is now `applyDungeonExitRules()` and branches: loot
  still on the floor returns early as before; all loot gone with enemies still alive calls the new
  `DungeonRotation.onDungeonLooted()`; nothing left at all keeps the existing `onDungeonClear()`
  path and its round-122 `[TFR-DungeonClear]` gate. One call site, unchanged, in `exitDungeon()`.
- **`forge-gui-mobile/src/forge/adventure/world/World.java`** — new persisted
  `Map<String,Integer> poiLootedDay` + `getPoiLootedDay()`, alongside the existing
  `poiDespawnDay`/`poiRespawnDay`/`poiFailedAttempts`: same `SaveFileData.storeObject`/`readObject`
  pattern, same `containsKey` load guard (so pre-round-128 saves load unchanged), and cleared in
  `generateNew()`'s reset block with the other three.
- **`forge-gui-mobile/src/forge/adventure/util/DungeonRotation.java`** (mod-new file, listed here
  because the round touches stock files with it) — new `onDungeonLooted()`; `hidePoi()`,
  `activateFromReserve()` and `onQuestTargetBound()`'s force-spawn branch also clear the new marker.
- **`forge-gui-mobile/src/forge/adventure/data/TuningData.java`** (mod-new) — new
  `dungeonLootedDespawnFactor` (0.5), read from the plane's `config tables/settings.json`.

### 2026-09-06 Stale pickup labels + winged enemies (round 129)

- **`forge-gui-mobile/src/forge/adventure/stage/GameStage.java`** - new tracked `statusMessages`
  list with `addStatusMessage(Actor)` / `clearStatusMessages()`. Floating pickup labels used to be
  added straight to the stage by `AdventurePlayer.addStatusMessage()`, so nothing ever removed one
  whose 3-second action had not finished when the player left the map; the next map loaded into the
  `MapStage` singleton resumed it. `[TFR-StatusMessage]`.
- **`forge-gui-mobile/src/forge/adventure/player/AdventurePlayer.java`** - `addStatusMessage()` now
  hands the label to `getCurrentGameStage().addStatusMessage(...)` instead of `addActor(...)`.
- **`forge-gui-mobile/src/forge/adventure/stage/MapStage.java`** - `loadMap()` calls
  `clearStatusMessages()` beside the existing actor sweep.
- **`forge-gui-mobile/src/forge/adventure/stage/WorldStage.java`** - `clearCache()` clears both
  stages' labels, next to round 126's `cancelPendingActions()` pair.
- Plane data (not an engine file, noted for the round): 51 `enemies.json` entries gain
  `"flying": true`. `flying` is a MOVEMENT flag - a flying enemy skips the collision test and the
  navigation mesh and beelines at the player - not the MTG keyword.

### 2026-09-06 Genetic deck override gate (round 130)

- **`forge-gui-mobile/src/forge/adventure/data/ConfigData.java`** - new
  `disableGeneticDeckOverrides` (default **false**, so stock planes are untouched; only this
  plane's config.json sets it true).
- **`forge-gui-mobile/src/forge/adventure/data/EnemyData.java`** - `generateDeck()` clears
  `canUseGeneticAI` when that flag is on, which disables BOTH of upstream's Hard/Insane deck
  substitutions at once: the LDA archetype branch here, and the
  `DeckgenUtil.getRandomOrPreconOrThemeDeck` branch inside `CardUtil.getDeck()` that the same
  boolean is passed into. Plus a `private static` once-per-session log guard
  (`[TFR-DeckOverride]`); the class's explicit `serialVersionUID` (round 90) means that cannot
  move the save format.
- Not changed, deliberately: `DuelScene`'s genetic fallback for a MISSING deck file (an error
  path), and `CardUtil.getDeck`'s `isFantasyMode` half (Chaos mode).

### 2026-09-07 Capital-visit flag + quest offer gate (round 132)

- **`forge-gui-mobile/src/forge/adventure/scene/TileMapScene.java`** - sets
  `visitedCapital_<colour>` on entering a `type: "capital"` POI whose name resolves through
  `ColorReputation.colorOfTown()`, beside the existing Ring City / surviving-town entry flags.
  Drives quests 87-91 (both their completion objective and their offer gate). `[TFR-MainQuest]`.
- **`forge-gui-mobile/src/forge/adventure/util/AdventureQuestController.java`** - new out-of-band
  `requiresCharacterFlagUnset` template gate (`questRequiresFlagUnset` map + `flagGateBlocks()`),
  populated by the same untyped quests.json pass that reads `offerProbability`, and applied both in
  the tag-matching filter and in the no-tag-match fallback. Deliberately NOT a field on
  `AdventureQuestData` - see that class's own note on save corruption.

### 2026-09-07 Arena payout rebuild (round 133)

- **`forge-gui-mobile/src/forge/adventure/scene/ArenaScene.java`** - new `defeatedThisBracket`
  (every opponent beaten this bracket, in round order) and `capitolPayoutBracket` (set from the
  same `tierWeighted` expression round 125 uses). `done()` pays one rare+ card themed to EACH
  beaten opponent for Player-Capitol-level-1 and AI-capital brackets, where the pre-existing drop
  paid a single card themed to the last one and only in Challenge mode. `[TFR-ArenaPayout]`.
  Mutually exclusive with the Challenge drop, which requires `challengeMode`.
- Plane data (not engine files, noted for the round): the `arena` property's reward tables in the
  five AI capital maps and `player_capital.tmx` are now 200/150/150 gold (the engine sums tables
  0..roundsWon-1, so cumulative 200/350/500) plus each arena's own guaranteed win item. The Player
  Capitol's twelve probabilistic item rolls are gone. `arenaChallenge` (level 2) is untouched.

### 2026-09-07 Medal slot, Arena payout tiers, Torch banner (round 134)

- **`forge-gui-mobile/src/forge/adventure/scene/ArenaScene.java`** - `capitolPayoutBracket` widened
  to `!isChallenge && fromBuilding && (playerOwnedArena || isAiCapitalArena())` (a level-2 arena in
  Normal mode plays the level-1 tables and must pay the level-1 way); new
  `challengePayoutBracket` (level-2 Challenging, two-Mythic cap on the themed cards) and
  `chestArenaBracket` (the standalone Illegal Arena, bonus roll 0.4/Uncommon instead of
  0.3/Common). The bonus-item roll lives in `done()` because a reward table cannot cap an item
  across rounds.
- **`forge-gui-mobile/src/forge/adventure/util/ChestEvents.java`** - the Illegal Arena's tables are
  now 200/150/150 gold plus its existing win item, instead of two empty tables and an item.
- **`forge-gui-mobile/src/forge/adventure/stage/ConsoleCommandInterpreter.java`** - the Torch pulse
  banner is gated on a new `torchPulseSeen` character flag (first use only).
- Plane data: `ui/inventory.json` + `ui/inventory_portrait.json` gain `Equipment_Medal`;
  `world/items.json` Jewel of Blessings 30,000/Uncommon -> 12,000/Rare;
  `maps/map/towns/player_capital.tmx` `arenaChallenge` rebuilt (300/200/300 gold, four item tiers
  0.25/0.40/0.15/0.05, one guaranteed win item, card entries removed).

### 2026-09-07 Tier-title display + weekly arena win (round 135)

- **`forge-gui-mobile/src/forge/adventure/data/EnemyData.java`** - `getTieredDisplayName()` strips
  ANY of the four tier labels from the front of a name, not only one that already matches this
  enemy's tier, so a stale title left by the round-116/118 re-tier cannot reach the screen. New
  `TIER_LABELS`. Identity is untouched - the raw name still keys quests, `.tmx` references,
  deck numbers and the save's kill-count/coin-ransom maps.
- **`forge-gui-mobile/src/forge/adventure/world/World.java`** - new persisted
  `Map<String,Integer> arenaWinWeek` + `getArenaWinWeek()` + `getCurrentWeek()` (day/7), following
  the poi* maps' store/read/containsKey/clear pattern. `World` is `SaveFileContent`, not
  `java.io.Serializable`, so the field cannot affect any save format.
- **`forge-gui-mobile/src/forge/adventure/scene/ArenaScene.java`** - `weeklyArenaKey()`
  (`<POI id>:L1|:L2`, null for a bracket with no POI) and the check at the top of `done()`: a full
  bracket win records the week, and a second full win at the same venue in the same week is
  refused with a notification and no payout. `[TFR-ArenaWeekly]`.

### 2026-09-07 Arena two-item cap (round 136)

- **`forge-gui-mobile/src/forge/adventure/scene/ArenaScene.java`** - new `capArenaItems(data, max)`
  called from `done()`: keeps at most two Item rewards, the two with the highest catalog `cost`,
  removing the rest back-to-front. Applied to the whole assembled payout (round tables + champion
  bounty + bonus roll) because only the total is capped, and deliberately BEFORE the Bronze Coin
  ransom reclaim, which returns the player's own item rather than paying loot.

### 2026-09-07 Granted equipment slots (round 137)

- **`forge-gui-mobile/src/forge/adventure/data/ItemData.java`** - new `grantsEquipmentSlot`, the
  slot name an item unlocks while worn. The class has an explicit serialVersionUID, so this cannot
  move the save format.
- **`forge-gui-mobile/src/forge/adventure/player/AdventurePlayer.java`** - `grantedEquipmentSlots()`,
  `slotCandidates()`, `dropUngrantedSlots()`, and `equip()` reworked to fill the first free
  candidate slot and to unequip from whichever candidate actually holds the item. `equip()` now
  also clears `isEquipped` on a DISPLACED item, which it never did before. `[TFR-EquipSlot]`.
- **`forge-gui-mobile/src/forge/adventure/scene/InventoryScene.java`** - a `*2` paperdoll slot is
  hidden unless something currently grants it.

### 2026-09-07 Teleporter reprice + exact (un-scaled) costs (round 138)

- **`forge-gui-mobile/src/forge/adventure/util/EconomyBuildings.java`** (mod file) - `buildCostFor()`
  gained its first location-dependent entry: `teleporterCost()` returns 100 shards (a scaled base,
  75/100/125/150 across Easy..Insane) at the Capitol and an exact 10 shards in a town.
  `MAX_TOWN_TELEPORTERS` 4 -> 5, so the network is six sites. New `exactCostLabel()`,
  `canAffordExactCost()` and `spendExactCostAction()` sit beside the 2026-08-12 multi-resource cost
  core as its un-scaled siblings, for prices pinned to a literal figure rather than to a base -
  Insane's x1.5 cannot land on 10 from any integer. `buildOption()` resolves the label, the
  affordability check and the deduction from one flag so they still cannot disagree.

### 2026-09-07 Cave champions + war champions (round 139)

- **`forge-gui-mobile/src/forge/adventure/data/ConfigData.java`** - new `caveChampionChance`
  (float, 0 = off), the per-cave chance of hosting one arena-exclusive champion.
- **`forge-gui-mobile/src/forge/adventure/util/Config.java`** - loads
  `config tables/war_champions.json` into a new `WarChampionData`, same plane-local /
  fallback-to-common pattern as spawn_tier_weighting.json; absent leaves it null (feature off).
- **`forge-gui-mobile/src/forge/adventure/data/BiomeData.java`** - `getEnemy()` appends the
  biome colour's war champions AFTER its difficulty filter (every arena champion is difficulty 3,
  which rank() only reaches at 150 wins) and, after the weighting branches, grants them a
  combined weight computed from the rest of the distribution so their share stays at the
  configured percentage as the tier targets move.
- **`forge-gui-mobile/src/forge/adventure/world/World.java`** - new persisted
  `Map<String,String> caveChampion` + `getCaveChampion()`, following the poi* maps'
  store/read/containsKey/clear pattern. Records BOTH outcomes of a cave's roll so re-entry can
  neither re-roll nor farm. `World` is `SaveFileContent`, not `java.io.Serializable`.
- **`forge-gui-mobile/src/forge/adventure/stage/MapStage.java`** - `prepareCaveChampion()` runs
  before the layer loop and picks one enemy placement for promotion; the `case "enemy"` branch
  swaps that placement's EnemyData after the territory re-theme. Bosses and quest-tagged enemies
  are never displaced. `[TFR-CaveChampion]`.
- New mod files: `data/WarChampionData.java`, `util/WarChampions.java`, `util/CaveChampions.java`.

### 2026-09-07 Review fixes S1-1/S2-4/S2-6 and the round-141 batch

- **`forge-gui-mobile/src/forge/adventure/world/WorldSave.java`** - S1-1: the world-load catch no
  longer regenerates; it logs `[TFR-Load] WORLD LOAD FAILED`, prints the exception and returns
  false. New `getLastLoadError()`/`clearLastLoadError()` and `removePointOfInterestChanges()`.
- **`forge-gui-mobile/src/forge/adventure/world/World.java`** - S2-4: new `purgePoiState()`,
  forgetting every id-keyed record a re-keyed POI leaves behind.
- **`forge-gui-mobile/src/forge/adventure/util/TerritoryControl.java`** - S2-4:
  `forgetTownState()` and its call from the capture/sack/revert path.
- **`forge-gui-mobile/src/forge/adventure/util/TownRestoration.java`** - S2-4: the same destroy on
  `captureTownForPlayer()`.
- **`forge-gui-mobile/src/forge/adventure/util/Config.java`** - S2-6: `fatalDataError` recorded
  when config.json or settings.json exists but does not parse.
- **`forge-gui-mobile/src/forge/adventure/scene/SaveLoadScene.java`** - shows both of the above.
- **`forge-gui-mobile/src/forge/adventure/scene/ArenaScene.java`** - round 141:
  `weeklyArenaLocked()`/`notifyWeeklyArenaLocked()` gate ENTRY at the button, the click and the
  fee point, replacing round 135's payout-only gate.
- **`forge-gui-mobile/src/forge/adventure/scene/InventoryScene.java`** - round 141: `itemLocation`
  is cleared on every refresh (it never was - a repeatable-gold sell exploit), `setSelected(null)`
  after sell/delete, and sell() verifies the player still holds the item.
- **`forge-gui-mobile/src/forge/adventure/data/RewardData.java`** - round 141: new `cardNames`
  pool field, drawing `count` distinct cards at random. Card-side twin of `itemNames`.
- **`forge-gui-mobile/src/forge/adventure/util/ChestEvents.java`** - round 141:
  `pickRandomArchmage()` falls back to arena-exclusive enemies with 100+ life, so Arzakon and
  Nephilim Epochal stop being unreachable.

### 2026-09-07 Frontier spawns (round 142)

- **`forge-gui-mobile/src/forge/adventure/data/BiomeData.java`** - `getEnemy()` also injects
  `FrontierSpawns.injectFor(name, filteredEnemies, difficultyFactor)` and grants it a share.
  Both injected groups (war champions, frontier spawns) are now weighted against the ordinary
  pool total captured before either is added, so their configured shares are independent.
- **`forge-gui-mobile/src/forge/adventure/util/Config.java`** - loads
  `config tables/frontier_spawns.json` into a new `FrontierSpawnData`; absent leaves it null (off).
- New mod files: `data/FrontierSpawnData.java`, `util/FrontierSpawns.java`.

### 2026-09-07 S4-6 and reputation with the player's own colours (round 143)

- **`forge-gui-mobile/src/forge/Adventure.java`** - the render loop's
  `catch (IllegalStateException | NullPointerException)` no longer silences outright. First
  occurrence of each distinct exception (class + top stack frame) is logged with its trace, then a
  count every 600 repeats. `[TFR-Render]`. Closes code review S4-6.
- **`forge-gui-mobile/src/forge/adventure/data/DialogData.java`** - new ActionData field
  `addColorReputationPlayerColors` (displayed points).
- **`forge-gui-mobile/src/forge/adventure/util/MapDialog.java`** - handles it.
- **`forge-gui-mobile/src/forge/adventure/util/ColorReputation.java`** - new
  `addToPlayerColors(displayPoints, why)`: a FLAT add to each colour of the player's own identity,
  deliberately not the zero-sum applyPattern() wheel (a five-colour player would net zero).

### 2026-09-07 Save format version + memory line (round 144)

- **`forge-gui-mobile/src/forge/adventure/world/WorldSave.java`** - `SAVE_FORMAT_VERSION` /
  `MIN_READABLE_SAVE_FORMAT`, written into mainData by `save()` and checked at the top of `load()`
  BEFORE any sub-object is read. A refusal sets `lastLoadError`, which the menu already surfaces.
  Absent key reads as version 1, so older saves are unaffected. Code review 4.7.
- **`forge-gui-mobile/src/forge/adventure/stage/WorldStage.java`** - `[TFR-Mem]` native/Java heap
  line on the day tick, next to `[TFR-DayTick]`. Code review 4.1.

### 2026-09-07 Roaming guards (round 145)

- **`forge-gui-mobile/src/forge/adventure/player/AdventurePlayer.java`** - holds the roster
  (`getRoamingGuards()`), saved/loaded through RoamingGuards, cleared in clear(). New
  `clearDeck(int)` and `setDeck(int, name, cardList)` for the guard deck round-trip.
- **`forge-gui-mobile/src/forge/adventure/scene/DuelScene.java`** - `useGuardLoadout(deck, life)`:
  the "player" seat can fight with a deck that is in no deck slot, at its own starting life.
  Cleared by initDuels() on every ordinary duel so it cannot leak into the player's next fight.
- **`forge-gui-mobile/src/forge/adventure/stage/WorldStage.java`** - five edits: the
  `currentMobIsGuardDuel` flag, interception before `TerritoryControl.onMageArrived()`, the
  per-frame `RoamingGuardRuntime.update()` on the same clock as the mages, `startGuardDuel()` /
  `simulateGuardDuel()`, and the guard branch at the top of `setWinner()`.
- **`forge-gui-mobile/src/forge/adventure/util/EconomyBuildings.java`** - Capitol-only Local/Roaming
  fork in `openManageGuardsDialog()`; the original dialog is now `openLocalGuardsDialog()`,
  unchanged. Four dialog helpers relaxed to package-private for RoamingGuardUI. New
  `payRoamingGuards()` in the weekly salary sweep.
- **`forge-gui-mobile/src/forge/adventure/util/DeckTesterSimulator.java`** - `runBatch` overload
  taking a starting life per seat, so a simulated guard fight matches a watched one.
- **`forge-gui-mobile/src/forge/adventure/util/Config.java`** - loads
  `config tables/roaming_guards.json`; absent leaves the feature off.
- New mod files: `data/RoamingGuardData.java`, `data/RoamingGuardConfig.java`,
  `util/RoamingGuards.java`, `util/RoamingGuardUI.java`, `util/RoamingGuardRuntime.java`.

### 2026-09-08 Roaming guard fixes + balance sheet (rounds 146-147)

- **`forge-gui-mobile/src/forge/adventure/scene/RewardScene.java`** - `enter()` re-opens the
  roaming-guard roster when returning from its Info scene (one-shot flag, same shape as the
  existing pendingEmptyBoosterNote).
- **`forge-gui-mobile/src/forge/adventure/scene/MapViewScene.java`** - the Details overlay also
  labels each player-owned town with its garrison (local guards plus any roaming guard assigned
  there), behind the same fog-of-war gate the Under Attack label uses.
- **`forge-gui-mobile/src/forge/adventure/scene/WorldStandingsScene.java`** - `balanceSheet` button.
- **`forge-gui-mobile/src/forge/adventure/stage/WorldStage.java`** - a guard duel fights an
  ante-free clone of the mage.
- **`forge-gui-mobile/src/forge/adventure/util/EconomyBuildings.java`** - `weeklyMineOutput()` /
  `weeklyBankInterest()` for the balance sheet, Balance Sheet buttons on the Bank and Exchange
  dialogs, and no salary for an unarmed roaming guard.
- New mod file: `util/BalanceSheet.java`.

### 2026-09-08 Weekly resource ledger + guard engagement colors (round 148)

- **`forge-gui-mobile/src/forge/adventure/player/AdventurePlayer.java`** - the four resource
  mutators (`takeGold`, `takeShards`, `setShards`, `takeWood`, `takeStone`) report the actual delta
  to `ResourceLedger`, and `defeated()` reports the gold it burns by direct field write. Holds a
  `ResourceLedger.Book`, saved/loaded/cleared beside the roaming guards.
- **`forge-gui-mobile/src/forge/adventure/util/EconomyBuildings.java`** - `processDaysPassed` and
  `payRoamingGuards` declare which ledger bucket their payouts belong to; the bank dialog's
  deposit/withdraw and the Exchange's trade declare IGNORED.
- New mod file: `util/ResourceLedger.java`.

### 2026-09-08 Constructed starter decks generated from race editions (round 149)

- **`forge-gui-mobile/src/forge/adventure/util/CardUtil.java`** - `generateDeck`/`getDeck` gained an
  edition-LIST flavour with a `restrictRewards` flag that stamps the mainDeck/template reward
  filters as well as the jumpstart pack pool. The single-`CardEdition` overloads delegate with the
  flag off, so existing behavior is unchanged.
- **`forge-gui-mobile/src/forge/adventure/util/EditionProgression.java`** - `raceEditionCodes(int)`.
- **`forge-gui-mobile/src/forge/adventure/util/Config.java`** - `starterDeck(..., int race)`; the
  Constructed branch builds from the race's expansions and falls back to an unrestricted rebuild if
  the result is under `minDeckSize`.
- **`forge-gui-mobile/src/forge/adventure/world/WorldSave.java`** - passes the race through.

### 2026-09-08 Standard + Pile starter decks follow the race sets (round 150)

- **`forge-gui-mobile/src/forge/adventure/util/Config.java`** - `racedStarterDeck()` (requested sets
  -> race's four -> unrestricted, logged at each widening), used by the Standard, Constructed and
  Pile branches of `starterDeck`.
- **`forge-gui-mobile/src/forge/adventure/scene/NewGameScene.java`** - Standard's starter-edition
  dropdown lists the chosen race's expansions plus an "all of them" entry and is rebuilt on race
  change; `getStartingEdition()` reads that list.

### 2026-09-08 Playtest fixes for the generated starter decks (round 151)

- **`forge-gui-mobile/src/forge/adventure/util/CardUtil.java`** - the card predicate requires rarity
  AND edition on one printing when editions are constrained (rarity-only behavior unchanged);
  `remapToEditionList` gained a rarity-preference overload; `generateCards` honours
  `RewardData.maxCopies`; deck generation stamps that at 4.
- **`forge-gui-mobile/src/forge/adventure/data/RewardData.java`** - `maxCopies` field + copy ctor.
- **`forge-gui-mobile/src/forge/adventure/stage/GameHUD.java`** - `clearNotifications()` clears the
  pane's queued Actions, not just the label.
- **`forge-gui-mobile/src/forge/adventure/scene/InfoTextScene.java`** - the decorative Window frame
  is `Touchable.disabled` so its toFront() cannot bury the page.
- **`forge-gui-mobile/src/forge/adventure/player/AdventurePlayer.java`** - `grantRingGift("all")` is
  once per character, gated on a `ringGiftGranted` flag.

### 2026-09-08 Guard duel result + item dialog + guard travel (round 152)

- **`forge-gui-mobile/src/forge/adventure/scene/DuelScene.java`** - when `hostedMatch.getGame()` is
  already null in GameEnd(), the winner is read from `hostedMatch.getMatch()` (which outlives the
  game) instead of defaulting to false. Fixes every watched AI-vs-AI duel being scored a loss.
- **`forge-gui-mobile/src/forge/adventure/scene/InventoryScene.java`** - the use-confirmation dialog
  is rebuilt per item instead of cached in a field, and its label wraps.
- **`forge-gui-mobile/src/forge/adventure/scene/MapViewScene.java`** - roaming guard dots; minZoom
  0.25 -> 0.12.

## Upstream merge log

- **2026-09-10 - merged upstream `master` @ `06a3c05731c` (Forge 2.0.15-SNAPSHOT, 09.09 daily; 35 commits,
  162 files, 122 `.java` since the previous merge point `6155ef58a50`).** **One conflict** - `UIScene.enter()`:
  upstream `b09a3d3f009` replaced the last-screenshot backdrop body with a pixelating-shader `BaseDrawable` on the
  lines carrying the mod's `Forge.lastPreview != null` guard (2026-08-26); resolved by keeping the guard above
  upstream's body, with a merge note in the comment.
  - The base install `E:\GAMES\Forge_2` is this exact commit: `.installationinformation` `2.0.15-SNAPSHOT-09.09`,
    `build.txt` `2026-09-09 18:24:56`; probes: HAS `c8630845165` (`withering_curse.txt`) and `8aa0c3d0a35`
    (`Reality Fracture.txt`), LACKS `43e6b5a1397` (`dead_ringers.txt`). The 22 upstream commits after it were
    deliberately NOT merged (ten carry Java: UIScene backdrop/deck-editor colours `e6476f9ee7b`, edition code on the
    rewards screen `63852c37ae0`, server-URL dialog on mobile `a5f4f9e4796`, auto-pass network `5b4ae4601a2`,
    GdxRuntimeException buffer fix `a8cf0d3b78c`, bulk image download `e79c785f809`, online draft polish
    `2481801e679`) - they are the next merge's first item.
  - Files touched by both sides, all auto-merged except UIScene, mod-added lines re-checked and all present:
    `forge-game/.../player/Player.java` 11, `Forge.java` 7, `UIScene.java` 50, `en-US.properties` 5,
    `ForgeConstants.java` 7, `ForgePreferences.java` 4.
  - Upstream's changes worth knowing about, per file this doc tracks: `Forge.java` gained the CDN language
    download flow and the adventure-mode CDN-popup suppression (`7a55a0bcee7`); `UIScene.java` the shader backdrop
    and scene disposal on exit; `Player.java` lost six hidden-keyword lines (`cda00a3b96f`); `ForgeConstants`/
    `ForgePreferences` gained CDN language keys. `forge-gui/pom.xml` adds `gson 2.13.2`.
  - Nothing under `res/adventure` changed upstream in this range; no Android file either.
- **2026-09-06 - merged upstream `master` @ `6155ef58a50` (Forge 2.0.15-SNAPSHOT, 09.06 daily;
  14 commits, 37 files, 15 `.java` since the previous merge point `042b3267af7`).** **Zero
  conflicts** - all seven files both sides had touched auto-merged.
  - The base install `E:\GAMES\Forge_2` was already at this daily (`.installationinformation`:
    `forge-installer-2.0.15-SNAPSHOT-09.06.jar`, `build.txt` `2026-09-06 18:21:40`). Content
    probes place it at upstream `53a103721d6` exactly - it HAS `53a103721d6`'s `exhume.txt` edit
    and LACKS the tip's `Crypt_of_Broken_Rules.tmx` - so the daily is 13 of the 14 commits. The
    14th is data-only (Realm of Legends plane) with no `.java`, so repo and install run identical
    engine code.
  - Files touched by both sides, all auto-merged, 354 mod-added lines re-checked and all present:
    `GameLauncher` 19, `Forge` 4, `SaveLoadScene` 43, `UIScene` 50, `GameHUD` 227, `SaveFileData` 4,
    `FSkin` 7. The merge delta on each was also read in the other direction: only upstream hunks.
  - Upstream's changes worth knowing about, per file this doc tracks:
    - `UIScene.java` - upstream appended `FrameRate.getInstance().sampleAdventure(stage.getBatch(),
      Forge.showFPS)` to `render()`. The mod's round-120 `toFront()` z-order re-assert in `act()`,
      its `dialogBodyMaxWidth()` wrap and its `Forge.lastPreview` null guard in `enter()` are all
      outside that hunk and untouched.
    - `GameHUD.java` - the stage no longer borrows `gameStage.getBatch()`; it builds its own and
      exposes the game stage's through a new `getBatch()` override, so callers are unaffected. The
      singleton is created once per process, so the extra `SpriteBatch` is not a per-load leak.
    - `Forge.java` - `delayedSwitchBack()` -> `delayedSwitchBack(String title, String message)`,
      four new `FrameRate.updateHistoricalPeak()` calls, `HIGH_SPRITES_CAP` 700 -> 800, scene
      imports collapsed to `forge.adventure.scene.*`. The mod's four added lines are elsewhere.
    - `SaveLoadScene.java` - new `showMessage(title, message)` used by the above.
    - `SaveFileData.java` - the one `delayedSwitchBack` call site, updated by upstream itself.
    - `GameLauncher.java` - `Lwjgl3ApplicationConfiguration.useGlfwAsync()` replaces the
      `SharedLibraryLoader.isMac` GLFW check; the mod's window-icon block is untouched.
    - `FSkin.java` - a `/` separator added to five theme-missing messages; the mod's `mkdirs()`
      fix for the skins cache dir is intact.
  - Stock files the mod does not edit, changed by upstream and worth noting because they change
    play: `AiBlockController` (won't block with creatures that die before dealing damage) and
    `ChangeZoneAi` (shared fetches when other players have nothing to retrieve).
  - Android identity: upstream's delta contains **no** Android file. The full revert-watch list in
    ANDROID_RELEASE.md was re-checked anyway and every item is intact (manifest package,
    `app_name`, publicfileprovider authority, Sentry deleted/disabled, `GITHUB_FORGE_URL`,
    `GitLogs`/`AssetsDownloader` `tfr-v` markers, `ASSETS_DIR`, `RES_PKG_FALLBACK`, the
    `setUsingAppDirectory` sniff, `AutoUpdater` force-disabled).
  - No edition-code renames (only `Reality Fracture.txt` changed, in place). `README.md` untouched
    by upstream - no "keep ours" resolution needed this round.
  - **Shared (non-plane-scoped) note:** the tip commit fixes
    `forge-gui/res/adventure/common/sprites/enemy/beast/smallmammals/weasel.atlas` (first line said
    `rabbit.png`). The 09.06 daily predates the fix and the packager takes `adventure/common` from
    `BASE_INSTALL`, so the plane's two weasel enemies still render as a rabbit until the base
    install is refreshed. Upstream's bug, upstream's fix, no mod action.
  - `engineBuildVersion` bumped to `2.0.15-SNAPSHOT-09.06` in the plane's config.json. Round 127 in
    MOD_CHANGELOG.md.

- **2026-09-04 - merged upstream `master` @ `042b3267af7` (Forge 2.0.15-SNAPSHOT, 09.05 daily
  build = the exact commit `E:\GAMES\Forge_2` was built from; 25 commits, 101 files, 63 `.java`
  since the previous merge point `c817743ecbd`).** One textual conflict:
  - `WorldSaveHeader.java`: the round-90 `serialVersionUID` pin vs upstream's new comment on
    `previewImageWidth` - kept OURS (the pin), adopted the comment.
  Thirteen files touched by both sides auto-merged; every mod-added line verified present
  afterwards (2,010 lines, 0 missing). Android identity files unchanged by upstream. No edition
  code renames. Round 114 in MOD_CHANGELOG.md.

- **2026-09-02 — merged upstream `master` @ `c817743ecbd` (Forge 2.0.15-SNAPSHOT, 09.01 daily
  build - the exact commit `E:\GAMES\Forge_2` was built from; 34 commits, 133 files, 80 `.java`
  since the previous merge point `8c7e9afb8e6`).** Four textual conflicts, all one cause: upstream
  renamed `Forge.takeScreenshot()` -> `ScreenUtil.getInstance().takeScreenshot()` and
  `Assets.getFileHandle()` -> `Forge.getAssets().getFileHandle()` on lines the mod had extended.
  - `ArenaScene.java`, `MapStage.java`, `WorldStage.java` (x1 each): the `TransitionScreen` duel
    intro line where the mod passes `getTieredDisplayName()` and `.withEnemyStatKey()` - kept OURS,
    adopted the `ScreenUtil` call. The mod's other `Forge.takeScreenshot()` sites in the same files
    (Arena rematch/leave, Capitol-defense and chest duels, console `teleport`) were outside the
    conflicts and were renamed by hand, as was the mod-new `EconomyBuildings.travelTo()`.
  - `WorldStage.java` imports: upstream added `GameScene` on the line the mod added `InfoTextScene`
    - kept both.
  - `WorldStage.java` POI-entry block: the mod's color-standing entry bar, capital toll and
    Legendary warning checks precede upstream's new `OverlayText` "L O A D I N G" + `startPause(1f)`
    wrapper around `autoSave/loadPOI/checkOut/visit` - kept the mod's checks (each `continue`s
    before the load) and adopted upstream's wrapper for the load itself.
  - `FSkin.java`: the mod's `mkdirs()` fix for the missing skins cache dir (2026-08-19) vs
    upstream's accessor rename - kept the fix with the new accessor.
  - Verified: a script re-checked every mod-added line (merge-base -> round 83) in the 19 files
    both sides touched - 4,422 lines, all present, the only differences being the six deliberate
    rewrites above. Android identity trio, `GITHUB_FORGE_URL`, `AssetsDownloader` tags,
    `forge-gui-android/pom.xml` stamps, icons, splash, `Zone.java`: none in upstream's delta, all
    intact. `README.md` untouched by upstream.
  - Data consequence: Conflux's edition code is now `CFX` (`Alias=CON`). The plane's 20 `CON`
    references (enemy reward `editions`, `Kaleidostone|CON` item printing, 17 legend decks) were
    swept to `CFX` - plane data, so not otherwise tracked here, but the trigger was upstream.
  - `engineBuildVersion` bumped to `2.0.15-SNAPSHOT-09.01` in the plane's config.json.
  - `standalone-packaging/build_standalone.py` (not an engine file; listed because it gates
    deploys): the static-asset marker now includes `BASE_INSTALL/build.txt`'s stamp, so a
    same-jar-name snapshot reinstall forces the full stock-asset copy.

- **2026-08-27 — merged upstream `master` @ `8c7e9afb8e6` (Forge 2.0.15-SNAPSHOT, 08.26 daily
  build; 55 commits, 160 files since the previous merge point `06019e99eed6`).** Seven textual
  conflicts across four Java files plus two binary PNGs:
  - `GameStage.java` (×2): upstream rewrote movement input around per-source vectors
    (`keyboardInput`/`controllerInput`/`touchInput`/`touchKnobInput`) with a central every-frame
    gate that zeroes everything while paused / dialog-up / controls-frozen. That gate SUBSUMES the
    mod's 2026-08-09 `dialogOnlyInput` guards on touch-steering and keyDown movement, so both
    hunks took upstream's side (a comment at the gate records the subsumption). The mod's
    Capitol-defense check, showDialog `stop()`, and touchDown/touchDragged early-outs all
    survived outside the conflicts.
  - `DuelScene.java` (×2): upstream's `Localizer localizer` local-variable refactor vs the mod's
    Auto-Sell/Buy Back buttons and tiered boss names - kept the mod's logic, adopted the
    `localizer` naming. Upstream's plain `enemy.getName()` in the boss intro rejected in favor of
    the mod's `enemy.getTieredDisplayName()`.
  - `SettingsScene.java` (×2): same localizer refactor vs the mod's Fog of War and Inn-tournament
    simulation setting rows - kept both rows, adopted `localizer`.
  - `TransitionScreen.java`: upstream draws its new full-art logo at 1x with landscape
    recentering; the fork keeps its 300x300 TFR icon, so the mod's 4x sizing + worldgen skip
    stayed (kept HEAD).
  - `adv_logo.png`: kept the fork's TFR icon (round-16 rebrand) over upstream's new logo art.
  - `sprite_adventure.png`: upstream's art refresh grew the atlas 304x606 -> 320x620 and redrew
    the lower half; `FSkinProp` regions unchanged. Took upstream's atlas and re-baked the TFR
    icon into `ICO_ADVLOGO` (2,2,300,300); pixel-verified logo region == ours, everything
    outside == upstream.
  - Auto-merged art worth knowing about: upstream's adventure art refresh (`title_bg.png` /
    `title_bg_portrait.png` start-menu background, `adv_bg_*.jpg` duel backdrops,
    `adv_bg_splash.png`, shop/tavern/arena `common/ui` art) came in clean - the fork never
    touched those files, and the plane has no overrides for them. `engineBuildVersion` bumped to
    `2.0.15-SNAPSHOT-08.26` in the plane's config.json.

- **2026-08-19 — merged upstream `master` @ `06019e99eed6` (Forge 2.0.15-SNAPSHOT, 2.0.15-08.19
  daily build; 200 commits, 732 files since merge-base `8c52e257e999`).** Only two textual
  conflicts: `GameLauncher.java` (upstream consolidated `setHdpiMode` while we added the
  window-icon block next to it - kept both, one `setHdpiMode`) and `QuestLogScene.java` (upstream
  added quest tracking with a `[BLACK]`/gradient header on the same line as our `QuestExpiry`
  countdown suffix - combined: `headerCode + name + suffix`). Everything else auto-merged; a
  12-agent verification pass confirmed every documented mod edit above survived and is still
  invoked. Upstream's only overlaps with mod-edited files were benign: `isAndroid()`→`isMobile()`
  swaps (World.java, adventure Config.java), an additive `PointOfInterest.getCenter()` +
  nav-arrow accuracy fix (WorldStage.java), a desktop-only pause-music checkbox
  (SettingsScene.java), mindslave/counter-cleanup refactors in untouched regions of `Game.java`,
  and a whitespace fix in `MatchController.java`. Our 61 hand-extracted round-22 card files and
  the resynced `buildingsbosses.atlas` were byte-identical to upstream's canonical copies.

## Standalone-game identity round (2026-08-19, MOD_SCOPE.md #89 part 2)

- **`forge-gui/src/main/java/forge/localinstance/properties/ForgeProfileProperties.java`** —
  the fork's data-dir identity: `getDefaultDirs()` now defaults to `ForsakenRealms` app folders
  (originally `ForgottenRealms`; renamed with the round-53 "Forsaken Realms" rebrand, commit
  3180f4aa6b7 - update greps accordingly)
  (`%APPDATA%\ForsakenRealms` / `%LOCALAPPDATA%\ForsakenRealms\Cache` on Windows, equivalent
  renames on mac/linux) so a stock Forge install on the same machine is never touched; new private
  `getStockForgeCacheDir()` reproduces upstream's unrebranded cache logic, and `load()`'s
  `cardPicsDir` DEFAULT now points at stock Forge's `pics/cards` (user decision 2026-08-19: card
  art is gigabytes and must be shared with an existing Forge install rather than re-downloaded);
  `save()`'s default-comparison updated to match. A `forge.profile.properties` file still
  overrides all of it. NOTE for dev machines: 2.0.15+ builds of this fork read/write
  `%APPDATA%\ForgottenRealms` — existing test saves under `%APPDATA%\Forge\adventure\The
  Forsaken Realms` must be copied over once (the old 2.0.14 install at `E:\GAMES\FORGE` is
  unaffected, its jars predate this change).
- **`forge-gui-mobile/src/forge/assets/AssetsDownloader.java`** — `checkForUpdates()` gets a
  desktop-only early-out (`if (!GuiBase.isAndroid()) { run(runnable); return; }`) killing the
  stock-Forge updater: on a pinned fork the "New Version Available" prompt would fire on nearly
  every launch (upstream ships daily snapshots) and accepting it downloads PLAIN Forge over the
  game. Every desktop path in the stock method ends in `run(runnable)` anyway — the entire
  remainder of the method is Android asset plumbing — so nothing else changes; Android (not
  shipped) keeps stock behavior. Upstream-merge watch: if upstream ever moves desktop update
  logic out of this method, re-apply the kill there.
- **`forge-gui-mobile-dev/src/forge/app/GameLauncher.java`** — window title `"Forge - <version>"`
  → `"The Forsaken Realms (Forge <version>)"` (rebrand decision 2026-08-19; engine version kept
  visible for bug reports).

New non-engine files (repo root `standalone-packaging/`, no upstream collision):
`build_standalone.py` (one-command package assembly: base install shells + repo jar + slimmed
res/adventure [common + plane only] + git-derived res overlay + docs; self-verifying),
`README.md` + `CREDITS.md` (player-facing, shipped in the package root and mirrored into the
plane folder alongside LICENSE.txt).

## Release-gate round (2026-08-19, MOD_SCOPE.md #89, twenty-seventh round)

- **`forge-gui/src/main/java/forge/localinstance/properties/AbstractPreferences.java`** — bug
  fix: `save()` now mkdirs the parent before writing; on a fresh data dir the preferences/
  folder didn't exist and FileWriter threw FileNotFoundException, silently losing the first
  save on every fresh install's first launch. Upstream-relevant fix (stock Forge has the same
  latent bug on a truly clean machine).
- **`forge-gui-mobile/src/forge/assets/FSkin.java`** — bug fix: `loadLight()` now creates
  `CACHE_SKINS_DIR` when missing (its own comment always said "ensure skins directory exists"
  but nothing did); a fresh cache dir short-circuited every launch to the jar's fallback_skin
  before `res/skins/default` was considered. iOS excluded (read-only bundle). Upstream-relevant.
- **`forge-gui/src/main/java/forge/localinstance/properties/ForgePreferences.java`** — fork
  default: `UI_ANTE` and `UI_ANTE_MATCH_RARITY` default "true" (upstream: "false"); the game is
  balanced around ante (user decision 2026-08-19). Watch on upstream merges: enum default
  strings.
- **`data/SettingData.java`** — `fogOfWarEnabled` initializes true (fork default; the per-plane
  fogOfWar config flag still gates the feature so stock planes unaffected).
- **`data/ConfigData.java`** — two new per-plane fields, both null on stock planes:
  `modVersion` (start-menu version tag) and `welcomePopupText` (one-time welcome dialog).
- **`scene/StartScene.java`** — appends " | TFR - v<modVersion>" to the version label when the
  selected plane sets modVersion.
- **`stage/MapStage.java`** — new `showWelcomePopup(String)` (dungeonFailedDialog idiom, plain
  OK dialog).
- **`scene/TileMapScene.java`** — `initializeDialogs()` shows the welcome popup once per save
  (questFlag `TFR_WelcomeShown`) before quest dialogs.

## v1.00 final playtest round (2026-08-20, MOD_SCOPE.md #89, twenty-eighth round)

- **`data/TuningData.java`** — two new tunables: `sideQuestDays` (default 30) and
  `baseAttackingMagesPerColor` (default 3, Normal-difficulty base).
- **`util/QuestExpiry.java`** — hardcoded `SIDE_QUEST_DAYS = 30` replaced by a
  `TuningData.sideQuestDays` read (no external references existed).
- **`util/TerritoryControl.java`** — `maxActiveMagesPerColor()` difficulty base now
  `TuningData.baseAttackingMagesPerColor` + fixed offsets (Easy -1/Normal 0/Hard +1/Insane +2);
  default reproduces the old `2 + index` ladder exactly. Log line extended.
- **`forge-gui-mobile/src/forge/screens/match/MatchController.java`** — ante re-roll confirm now
  `SOptionPane.showOptionDialog(["Keep","Re-roll"])` so Keep sits left / Re-roll right (user
  request); default selection unchanged (Keep).
- **`scene/DuelScene.java`** — ante Buy Back also re-adds the card to
  `getSelectedDeck().getMain()` (was collection-only).
- **`scene/TileMapScene.java`** — welcome popup skips the "Spawn" POI (tutorial intro dialog
  replaced it there; the intro menu's new Welcome option covers new games).

## Welcome-popup relocation (2026-08-20, MOD_SCOPE.md #89, twenty-ninth round)

- **`scene/TileMapScene.java`** — welcome-popup hook removed entirely (both spawn-dungeon
  placements collided with the tutorial intro dialog; a quests.json option variant soft-locked
  the tutorial).
- **`stage/WorldStage.java`** — new `showWelcomeDialog(String)` (showQuestsFailedDialog idiom).
- **`scene/GameScene.java`** — `enter()` shows the welcome dialog once per save
  (TFR_WelcomeShown) on first world-map entry; Config import added.
- (`stage/MapStage.java`'s `showWelcomePopup()` from round 27 is now uncalled - left in place
  as a harmless utility rather than churning the diff again this close to release.)

## Public-repo identity (2026-08-20, thirtieth round)

- **`README.md`** (repo root) — upstream Forge's readme REPLACED with The Forsaken Realms' own
  page (pitch, features, install, Discord/Ko-fi, build-from-source, Forge credit + GPL). Upstream
  merges will conflict here every time upstream touches their readme - always resolve to OURS.
- Repo renamed/made public: `TheSAguy/mtg-forge-mod` → **`TheSAguy/The-Forsaken-Realms`**
  (old URLs redirect; origin remote + all doc references updated).

## v1.00 feedback round (2026-08-21, thirty-first round)

- **`forge-game/src/main/java/forge/game/Game.java`** — `rerollAnte()` gained a per-Game
  recent-ante memory (ArrayDeque, 5-roll window) and a bounded re-draw loop preferring
  no-repeats + Uncommon-or-better; new private helpers `isUncommonPlus`/`rememberAnteRoll`.
  Only reachable via the adventure ante-re-roll UI - stock clients never call rerollAnte().
- **`world/World.java`** — `mapMarkerKey()`: name-based case for "Eldrazi Prison" ->
  "sidebosshard" minimap glyph (the Story-castle remap sent it to the generic dungeon glyph;
  minimap markers never read POI sprites, which is why four sprite fixes changed nothing there).
- **`util/EconomyBuildings.java` / `util/TownRestoration.java`** — all Wood/Stone cost
  components halved (build table, repair table, upgrade constants, town restore, Capitol).

## v1.01 round (2026-08-21, thirty-second round)

- **`stage/WorldStage.java`** — new `isLegendaryPoi()` + `showLegendaryWarningDialog()` and a
  Legendary gate in `handlePointsOfInterestCollision()` (after the reputation bars, before
  normal entry; Enter replicates the standard autoSave->loadPOI->checkOut->visit sequence).
- **`world/World.java`** — `mapMarkerKey()` Eldrazi name-check generalized to the "Legendary"
  questTag (9 POIs).
- **`scene/DuelScene.java`** — [TFR-AnteResult] probe extended: raw won/lost counts plus
  hasLost/anteZone/humanNotFound from the live Game, discriminating the two remaining suspects
  for the dungeon buy-back skip.

## Android round (2026-08-27, round 61) - engine files touched

Full rationale in MOD_CHANGELOG.md round 61; procedure in ANDROID_RELEASE.md. Files:
`ForgeConstants.java` (GITHUB_FORGE_URL -> fork repo; NETWORK_PLAY_WIKI_URL pinned to
Card-Forge), `AssetsDownloader.java` (tfr-v tags, GitHub release-download URLs, TFR update
message, version.txt stamp guard), `GitLogs.java` (tfr-v tag parse), `Forge.java`
(isUsingAppDirectory package sniff), `Config.java` (default plane prefers The Forsaken
Realms), `ForgeProfileProperties.java` (stock-Forge cardPicsDir default now desktop-only),
`AutoUpdater.java` (force-disabled - fork pins its engine), `HelpMenu.java` (wiki link pinned
to Card-Forge), plus the whole forge-gui-android module (manifest identity, Main.java
strings/paths/authority, pom release profile, icons, fallback skin). Upstream-merge watch:
every one of these is a revert target - see ANDROID_RELEASE.md "Landmines".

## Round 65 (2026-08-29) - save/load state-bleed fixes + player WFC model breakout

- **`stage/GameStage.java`** - the `WorldSave.onLoad()` handler now also clears
  `currentModifications` (the fly/sprint/hide debug-command timers). That map is runtime-only by
  design and correctly never persisted, but nothing told this long-lived singleton the timer was
  stale on a mid-session load, so an active `fly` survived loading a save. No `onRemoveEffect()`
  cleanup needed - the same handler discards and recreates the player Actor, taking any visual
  side effect with it.
- **`stage/WorldBackground.java`** - new `invalidateChunkTexture(chunkX, chunkY)`: disposes a
  chunk's baked GROUND texture and nulls the slot so `getChunkTexture()` lazily rebuilds it.
  Same evict-and-rebuild pattern as `onTileRevealed()`'s off-window branch. This is a SECOND,
  independent per-chunk cache from `chunksSprites`/`chunksSpritesBackground` (decoration Actors) -
  the distinction that caused the bug below.
- **`stage/WorldStage.java`** - new `invalidateBackgroundChunkTexture()` bridge, package-boundary
  twin of the existing `reloadBackgroundChunkObjects()`.
- **`world/WorldSave.java`** - `load()`'s post-load chunk sweep now refreshes BOTH per-chunk
  caches, not just the decoration-Actor one. Real, reported bug: after capturing a neutral town
  (which recolors terrain to the `player` biome) and then loading a save from before the capture
  WITHOUT quitting, the ground stayed player-tinted - only the doodads reverted. The 2026-08-25
  fix added the decoration-Actor half of this sweep; the ground-texture cache was never covered.

  Both bugs share one root cause worth remembering: `WorldStage`/`WorldBackground` are app-session
  singletons that outlive a save load, so ANY build-once cache or runtime timer they hold is a
  bleed-over candidate unless `load()` explicitly resets it. Quitting to the OS always looked fine
  precisely because that tears the singletons down.

### Round 65 addendum (2026-08-29) - VS-screen win/loss record always "0 - 0"

- **`forge-gui-mobile/src/forge/screens/TransitionScreen.java`** - new `enemyStatKey` field +
  fluent `withEnemyStatKey()` setter; the win/loss lookup in `drawBackground()` now keys off it
  instead of `enemyAvatarName`. `enemyAvatarName` was doing double duty as BOTH the on-screen
  label AND the statistics map key - fine in stock Forge where they are the same string, wrong
  here since `showEnemyTierInName` made the label `"Red Wizard (Adept)"` while
  `DuelScene.afterGameEnd()` stores the record under the raw `"Adept Red Wizard"`. Defaults to
  `enemyAvatarName`, so every un-updated caller (and stock planes) behave exactly as before.
- **`stage/WorldStage.java` (x3), `stage/MapStage.java`, `scene/ArenaScene.java`** - the five
  match-transition construction sites that pass `getTieredDisplayName()` as the label now chain
  `.withEnemyStatKey(<sprite>.getName())` to supply the raw identity key alongside it.
  `EventScene.java` deliberately NOT changed: it already passes the raw `getName()` and its own
  bracket records, so it was never affected.

### Round 65 addendum 2 (2026-08-29) - tournaments excluded from win/loss totals

- **`scene/DuelScene.java`** - `afterGameEnd()`'s statistics guard now also requires
  `eventData == null`, excluding Inn tournament matches from `PlayerStatistic.setResult()` and
  (same block) `SpawnTierWeighting.registerKill()`. Tournaments are played with
  `eventData.registeredDeck`, and `completedEvents`/`eventMatchWins()` already counted them
  separately - they were being double-counted into `winLossRecord`. Knock-on: `rank()` (overworld
  spawn difficulty) and the `winLossRatio()`-scaled sell price both read those totals.
- **`forge-gui-mobile/src/forge/screens/TransitionScreen.java`** - the caller-supplied
  `playerRecord`/`enemyRecord` branch and the global-record lookup are now if/else rather than
  the lookup unconditionally overwriting the explicit values; lets EventScene's bracket standings
  actually reach the screen.

### Round 65 addendum 3 (2026-08-29) - New Game / New Game+ statistics reset

- **`player/PlayerStatistic.java`** - `clear()` now also clears `completedEvents`, not just
  `winLossRecord`. Both are persisted/restored; a new game reuses the same final PlayerStatistic
  instance, so event stats leaked across characters.
- **`scene/SaveLoadScene.java`** - the `NewGamePlus` branch now calls
  `Current.player().getStatistic().clear()` with the other per-run resets. That path never calls
  `AdventurePlayer.clear()` (by design - it keeps cards/decks), so nothing else reset stats there.

## Round 66 (2026-08-29) - duel-win Wood/Stone loot tile

- **`character/EnemySprite.java`** - `applyGoldVariance()` now substitutes the Gold reward in
  place (`rewards.set(i, new Reward(Reward.Type.Wood|Stone, amount))`) instead of removing it from
  the array and calling `addWood()`/`addStone()` directly. Routes duel-win resources through the
  ordinary RewardScene/RewardActor loot-tile path (tile, glyph, grant on dismiss via
  `clearGenerated()` -> `AdventurePlayer.addReward()`, which has real Stone/Wood arms). The old
  direct-grant path and its null-icon floating status message are gone; the stale comment that
  justified them (claimed no Reward.Type and no atlas art - both untrue since 2026-08-10/27) is
  replaced with the real history.

## Round 67 (2026-08-30) - coin ransom, neutral town defense, engine crash fix

- **`forge-game/src/main/java/forge/game/zone/Zone.java`** (NEW stock-engine file for this fork)
  - `getCardsAdded()`'s empty-zone fast path returned an immutable `List.of()` while its other
  two returns hand back mutable `ArrayList` copies; `CardProperty.cardHasProperty()`'s
  "ExiledWithSourceLKI" branch sorts the result in place, so that path threw
  `UnsupportedOperationException` and killed the AI's `chooseSpellAbilityToPlay` future mid-turn
  (8 occurrences in one user session log). Now returns `Lists.newArrayList()`. Upstream
  Card-Forge bug, fixed locally - **revert-watch on the next upstream merge.**
- **`util/AdventureEventController.java`** - Jumpstart offer gate 10 -> 25 wins, extracted to
  `JUMPSTART_MAX_WINS`.
- **`player/AdventurePlayer.java`** - new persisted `coinRansomedEnemies` (Set<String>, saved as
  an ArrayList like `unlockedEditions`, containsKey-guarded on load for old saves) +
  `payCoinRansom()`/`owesCoinRansom()`/`reclaimCoinRansom()`/`BRONZE_COIN_ITEM`; new transient
  `suppressDefeatGoldLoss` consumed one-shot inside `defeated()` (gold only - life loss still
  applies). Both cleared in `clear()`.
- **`scene/DuelScene.java`** - "Use Bronze Coin" button on the ante-loss popup beside Buy Back;
  `coinRansomEligible()` (ordinary duels only - no events/Arena/bosses, and requires a coin),
  `payCoinRansomForAll()` (refunds the WHOLE ante, restoring to the active deck like Buy Back
  does, then skips the remaining per-card popups). `showAnteCardPopup()` gained an
  `onCoinRansom` parameter. Reclaim-on-win added to `afterGameEnd()`'s existing guarded funnel.
- **`util/TownRestoration.java`** - new `ARMORY_SHOP_OBJECT_ID` (48) + `hasWorkingArmory()`,
  reading the same `permanentlyBrokenShop_<id>` flag `seedFunctioningNeutralTowns()` writes.
- **`util/TerritoryControl.java`** - `NEUTRAL_TOWN_BASE_DEFENSE` (0.15) /
  `NEUTRAL_TOWN_ARMORY_DEFENSE` (0.05) / `NEUTRAL_TOWN_TARGET_WEIGHT` (0.85) +
  `isFunctioningNeutralTown()`; a defense roll in `onMageArrived()`'s neutral branch (flat repel
  chance, deliberately NOT a modifier on `attackerWinChance()` - see its comment) and the
  targeting weight applied in `dispatch()`'s existing weighted pick.

## Round 70 (2026-08-30) - doodad bleed, cave despawn, restricted-edition art

- **`stage/WorldBackground.java`** - `initialize()` now also clears `stage.getBackgroundSprites()`,
  not just `getSpriteGroup()`. loadChunk() parents doodads into BOTH groups (SpriteLayer ->
  foreground, BackgroundLayer -> background); only the foreground was cleared on reset, so
  background actors from a previous game survived into the next one for every chunk that had been
  loaded, and the array reallocation put them permanently beyond unLoadChunk()'s reach. This is the
  actor-parenting counterpart to round 65's two per-chunk cache resets.
- **`stage/MapStage.java`** - new `clearDungeonIfEmptied()`, called from `exitDungeon()`. Fires
  `DungeonRotation.onDungeonClear()` when a dungeon is left with no live enemies AND no uncollected
  RewardSprites. Previously onDungeonClear had exactly one trigger (the combat win where the killed
  enemy was the last standing), so a dungeon emptied any other way never despawned.
- **`util/MapDialog.java`** - option lists above `MAX_UNSCROLLED_OPTIONS` (6) are wrapped in a
  vertical ScrollPane instead of being added straight to the button table; at or below the
  threshold the original direct-add path is used unchanged, so no existing dialog's layout shifts.
  Also handles the new `DialogData.ActionData.pinShopType` action.
- **`util/CardUtil.java`** - new `remapAwayFromRestrictedEditions()`, called last inside
  `finishCandidate()` (after the variant roll, which can otherwise undo it). Swaps a
  restricted-edition printing for an unrestricted printing of the same card, preferring matching
  rarity and failing open. Needed because `isObtainableNotRestricted` is CARD-level and lets a
  restricted PRINTING through whenever the card has any unrestricted printing.
- **`data/SettingData.java` / `scene/SettingsScene.java`** - new `avoidRestrictedEditionArt`
  (default true) plus its Settings checkbox; `en-US.properties` gains
  `lblAvoidRestrictedEditionArt`.
- **`util/TerritoryControl.java`** - neutral-town [TFR-CaptureOdds] lines now carry a running
  session repel tally with the per-roll accumulated expectation, so the observed-vs-designed rate
  is readable without grepping and hand-computing it.
- **`standalone-packaging/build_standalone.py`** (not an engine file, but a revert-watch item all
  the same) - `assert_jar_is_fresh()` and `assert_target_not_in_use()`, both ordered before
  PACKAGE_OK.txt is removed and before any delete, so either abort leaves the live folder intact.

## Round 71 (2026-08-30) - shop-type blueprints + dialog crash fix

- **`stage/GameStage.java`** - `showDialog()` no longer blind-casts every button-table cell to
  TextraButton; new `collectDialogButtons()` descends through ScrollPane/Table. Round 70's
  scrollable option list put a ScrollPane in that table and made EVERY long dialog throw
  ClassCastException on open.
- **`util/MapDialog.java`** - honours `DialogData.pinLastOption`: when the list scrolls, the final
  option (Back / Not now) is added BELOW the scroll pane instead of inside it, so a long menu can
  never look like it has no way out.
- **`data/DialogData.java`** - new `pinLastOption` flag (opt-in; quest dialogs unaffected).
- **`player/AdventurePlayer.java`** - new persisted `unlockedShopTypes` (empty = legacy save = all
  unlocked) and `startingColorId`; `seedStartingShopTypes()` grants the color trio + race tribal
  shops at creation; `create()` gained a `startingColorId` parameter.
- **`world/WorldSave.java`** - `generateNewWorld()` gained a `startingColorId` parameter and passes
  it to `create()`. The ColorSet it already received is only a starter-deck lookup key.
- **`scene/NewGameScene.java`** - new `getStartingColorId()` returning the ACTUAL pick, or null for
  Chaos/Precon/CommanderPrecon/Custom (which all report a hardcoded White in getStartingColor()).
- **`util/EconomyBuildings.java`** - `isShopTypeUnlocked()` made public and given a real body;
  `shopTierOf()`, `blueprintShardCost()`, `shopDisplayName()`, `buyableCardCount()` (cached),
  `allChooserShopNames()`; chooser now sorts known-first, greys locked entries and shows card
  counts.
- **`stage/MapStage.java`** - `rerollShopType()` filters on `isShopTypeUnlocked` (closing the
  destroy-and-rebuild bypass); new `getShopTierPoolObjectIds()`.
- **`scene/RewardScene.java`** - new Buy Blueprint button, shown at ANY shop with an unknown type
  including AI towns, priced by tier in shards.
- **`util/ResourceSpawns.java`** - `grantRandomBlueprint()` + a 25% blueprint outcome on Mystery
  pickups, self-disabling via the existing ambush short-circuit idiom.
- **`util/ChestEvents.java`** - 25% blueprint roll ahead of the ordinary 1-of-6 event.
- **`data/ConfigData.java` / `data/RaceShopData.java` (new)** - `shopBlueprintsEnabled`,
  `raceShops`, `startingColorShopSuffixes`, and the three blueprint shard costs.

## Round 72 (2026-08-31) - live shop identity, blueprint fixes, ruined-town Inn

- **`stage/MapStage.java`** - new `applyShopType()` applies a type change to all six places identity
  lives (pin, ShopActor's ShopData, regenerated inventory, sign art, color-bar overlay, purchase
  history); `setShopType()`/`rerollShopType()` both delegate to it. New `ShopSignSprite` inner class
  replaces two drifted anonymous copies of the sign-visibility rule - the overlay's copy was missing
  `isPermanentlyBrokenShop()`, which is why ruined slots showed naked color bars. New
  `shopSignOverlays`/`shopSignAnchors` (so an overlay can be created later), `getShopActor(int)`,
  `getBuiltShopTypeNames()`. `rerollShopType()` now also filters out types already standing in the
  town. Feeds `EconomyBuildings.registerShopTiers()` as each map loads.
- **`character/ShopActor.java`** - `isDestroyed()` made public (the one-type-per-town scan needs to
  tell a built shop from rubble).
- **`util/EconomyBuildings.java`** - process-wide `globalShopTiers` accumulator +
  `registerShopTiers()`; `shopTierOf()` falls back to it (AI capitals have no per-slot tier pools);
  `allChooserShopNames()` unions it in so blueprint drops work in AI capitals;
  `blueprintStandingBlock()` / `blueprintShardCostHere()` implement the reputation ladder;
  chooser gained tier-level affordability greying, built-type greying, and Available/Built/Locked
  ranking via `chooserRank()`.
- **`scene/RewardScene.java`** - Buy Blueprint moved from row 4 (off-screen, past the 270-unit
  layout ceiling) to row 3; standing gate and standing-scaled price applied on the button, in the
  prompt, and again inside the confirm handler.
- **`scene/InnScene.java`** - new `isRuinedTown()`; card sales and Potion of False Life disabled in
  a still-ruined town, tournament untouched. Guarded in both click handlers, not just the buttons.
- **`util/Reward.java` / `util/RewardActor.java` / `player/AdventurePlayer.java`** - new
  `Reward.Type.Blueprint` with a `blueprint(String)` factory, its own RewardActor face (borrowed
  scroll/map icon with fallbacks) and label case, and an idempotent `addReward()` grant.
- **`util/ResourceSpawns.java`** - blueprint drops now reveal through `RewardScene` (loadRewards +
  `Forge.switchScene`, the two-step ChestEvents' card reveal already uses) instead of a HUD line.
- **`res/adventure/common/ui/items.json` + `items_portrait.json`** - `shopName` label y 0 -> 7.
- **`res/adventure/The Forsaken Realms/config tables/settings.json`** - `mineWeeklyGoldPayout`
  50 -> 75.

## Round 73 (2026-08-31) - map registry leak, Cartographer/coin/Inn rules, NG+ coin top-up

> *Rounds 73-76 were backfilled on 2026-09-01; they shipped with their detail in the commit
> messages rather than in this file. Reconstructed from those commits and their diffs.*

- **`stage/MapStage.java`** - `loadMap()` now also clears `shopSigns`, `shopSignOverlays`,
  `shopSignAnchors`, `shopCandidatePools` and `shopTierPools`. These are keyed by tmx object id, a
  number every town reuses, so a fresh town was inheriting the previous town's entries under the
  same id (stale sign overlays, and `isShopTypeRerollable()` answering from another town's pool).
  `refreshShopSignArt()`'s diagnostic now reports what happened to the ACTOR
  (created/swapped/suppressed/FAILED) rather than a fact about shops.json.
- **`util/EconomyBuildings.java`** - Cartographer land shops excluded from the blueprint system,
  keyed on `ShopData.sprite == "LandShop"` (the 5 basics only; `"NonbasicLandShop"` untouched), and
  filtered out of `allChooserShopNames()`. `buyableCardCount()` fixed to sum `min(count, pool)` per
  shelf instead of unioning every shelf's legal pool.
- **`data/AdventureEventData.java`** + **`util/AdventureEventController.java`** - player-town
  tournaments draw from race editions UNION unlocked sets; new `playerTownPoolStamp` re-rolls a
  cached Available event when the pool fingerprint changes; draft-block legality requires EVERY set
  in the block to be inside the pool, with a logged fallback to the global pool. Dead
  `localPriceModifier` parameter removed.
- **`scene/DuelScene.java`** - Bronze Coin ante refused when that enemy already holds one, with the
  dialog saying so instead of claiming "you have none".
- **`player/AdventurePlayer.java`** - New Game+ challenge-coin top-up (1 gold / 1 silver / 3
  bronze, granting only what is missing).
- **`scene/InnScene.java`**, **`scene/RewardScene.java`**, **`scene/SaveLoadScene.java`**,
  **`scene/GameScene.java`**, **`scene/TileMapScene.java`**, **`util/EditionProgression.java`** -
  `currentLocationChanges` now tracks the map the player is standing in rather than whichever shop
  screen was opened last (two callers spend real gold on it).

## Round 74 (2026-08-31) - New Game+ is now a new game

- **`player/AdventurePlayer.java`** - new `resetForNewGamePlus()` re-runs the nine per-run resets
  New Game does and NG+ was silently inheriting (`unlockedShopTypes`, `unlockedEditions`, research
  timers, `characterFlags`, `colorReputation`, `coinRansomedEnemies`, `events`, `blessing`,
  `partnerOverhealActive`, plus `reservePlayerEditions()`), and LOGS what it deliberately keeps.
  Root cause: `seedStartingShopTypes()` runs only from `create()`, which the NG+ path never calls.
  `updateDifficulty()` now copies `rewardMaxFactor`.
- **`scene/SaveLoadScene.java`** - NG+ branch calls `resetForNewGamePlus()`. Deliberately NOT
  `clear()`/`create()` - either would destroy the collection the player is carrying forward.

## Round 75 (2026-08-31) - timed Armory rarity, Capitol cooldown

- **`data/ArmoryRarityData.java`** (NEW) and **`util/ArmoryRarity.java`** (NEW) - week/venue rarity
  weight table. A banned rarity is expressed as a zero weight rather than a separate gate, so no
  Armory slot is ever dropped. Still exactly one `nextFloat()` per slot, preserving the seeded
  weekly stock.
- **`util/Config.java`** - loader for the plane's `config tables/armory_rarity.json`.
- **`data/ConfigData.java`** - new `armoryRarityGatingEnabled` flag (default false).
- **`data/TuningData.java`** - new `capitolTargetCooldownDays` (default 7).
- **`data/RewardData.java`** - `armoryRarityVenue` stamp; rarity roll swapped for the table lookup.
- **`util/EditionProgression.java`** - venue stamped onto the cloned `RewardData` in
  `restrictShopRewardsForCurrentTown`, the single point all six shop-generation call sites route
  through.
- **`util/TerritoryControl.java`** - per-color Capitol-attack cooldown, stamped at DISPATCH (not at
  resolution - a mage can be in transit for days). Three filter sites, the load-bearing one AFTER
  the in-flight exclusion block, which self-waives and would otherwise undo an earlier filter.
- **`world/World.java`** - `capitolTargetedDay` persistence for the above.

## Round 76 (2026-08-31) - Archaeologist blueprints, coin marker

- **`scene/PlayerStatisticScene.java`** - enemies holding one of the player's Bronze Coins draw the
  coin in the 16px spacer cell that row already reserved, so no other row moves. Keyed on the
  statistic's own map key, the same name DuelScene stamps the ransom with.
- **`util/EconomyBuildings.java`** - Archaeologist expeditions can return a shop-type blueprint
  (15%), drawn from the live chooser pool so Armory/fixed-land/Cartographer types can never appear;
  self-disables once every type is known. Does NOT unlock on generation - the RewardScene page
  grants what it shows, so unlocking here too would double-grant.
- *(The quest-28 spawn-dialog rework lives entirely in the plane's own `world/quests.json` - no
  engine file involved.)*

## Round 77 (2026-09-01) - 1-vs-N unblocked, Bronze Coin loot, Status button, spawn declustering

- **`character/CharacterSprite.java`** - `getAvatar(int)` clamps to the last Avatar frame the atlas
  actually carries and returns null when it has none (`monstrosity/umber_hulk.atlas` has zero);
  new `getAvatarCount()`. Unblocks every chained `EnemyData.nextEnemy` duel, which previously threw
  `IndexOutOfBoundsException` at seat 2 on 491 of 493 atlases.
- **`scene/DuelScene.java`** - per-seat avatar wiring flips a COPY of the cached Sprite (it is
  shared process-wide, so the in-place flip was alternating the portrait's facing between duels),
  skips wiring entirely for a null avatar, and applies the head sprite's `displayNameOverride` to
  seat 0 only. The Bronze Coin reclaim was REMOVED from here - it now happens at the payout sites.
- **`player/AdventurePlayer.java`** - new `appendCoinRansomReward(Array<Reward>, String)` clears
  the ransom mark and appends a `Reward.Type.Item` tile for the coin in one call;
  `reclaimCoinRansom()` demoted to its fallback for a failed item lookup.
- **`stage/WorldStage.java`** - calls `appendCoinRansomReward` on the overworld win payout (plus a
  new `com.badlogic.gdx.utils.Array` import). New `pickNonClusteringEnemy()` /
  `countSameEnemyNearby()`: re-rolls the ordinary weighted biome pick while the chosen enemy
  already has too many of itself alive near the player. War-tier bosses and quest-tag extra spawns
  are untouched.
- **`stage/MapStage.java`** - `getReward()` calls `appendCoinRansomReward` for dungeon/town wins.
- **`scene/ArenaScene.java`** - new `coinRansomFoesBeaten` notes coin-holding foes as rounds are
  won; the marks are cleared and the coins appended in `done()` with the bracket payout. Cleared at
  bracket start and on a zero-round exit so notes cannot leak between runs.
- **`scene/EventScene.java`** - the Inn tutorial coin refund is now a blocking dialog on this scene
  (new `showPendingCoinRefundDialog()` + `pendingCoinRefundItem`), raised after `finishRound()`.
  It was a `GameHUD.addNotification(...)`, and GameHUD's stage is not rendered while the player is
  in this scene, so the message animated and expired unseen.
- **`scene/WorldStandingsScene.java`** - new `status` button handler and a `lastGameScene` field;
  `instance()` gained a `Scene` overload (the no-arg one delegates).
- **`stage/GameHUD.java`** - `openWorldStandings()` passes `Forge.getCurrentScene()`, mirroring
  `logbook()`.
- **`data/ConfigData.java`** - new `spawnDuplicateLimitEnabled` flag (default false).
- **`data/TuningData.java`** - new `maxSameEnemyNearby` (2), `sameEnemyNearbyRadius` (220f),
  `sameEnemySpawnRerolls` (4).
- **`CLAUDE.md`** (repo root, not an engine file but tracked here for completeness) - Deploy
  section rewritten for the standalone packaging workflow; the retired `E:\GAMES\FORGE` splice
  procedure removed; `origin` corrected to `TheSAguy/The-Forsaken-Realms`.

## Round 78 (2026-09-01) - pre-release review fixes, v1.04 stamped

> *Backfilled in round 79 - round 78 shipped with its detail in the commit message only.*

- **`util/EconomyBuildings.java`** - both blueprint drop filters now use the legacy-aware
  `isShopTypeUnlocked()` (the raw `hasShopTypeUnlocked()` now has zero callers); the three pool
  `removeIf` calls moved AFTER the `globalShopTiers` union that was re-adding what they stripped,
  and widened to cover Armory-family names and names with no ShopData; new `shopTypeExists()`;
  `allChooserShopNames()` refuses to cache an empty universe.
- **`util/ResourceSpawns.java`** - same legacy-aware predicate on `grantRandomBlueprint`'s filter.
- **`stage/MapStage.java`** - `loadMap()` now calls `EconomyBuildings.invalidateChooserShopNames()`
  alongside the shop-pool clears; that method had never been called by anything.
- **`util/AdventureEventController.java`** - new `clearNextEventDate(String)` so an Inn pool-change
  re-roll can replace an event instead of deleting it.
- **`scene/InnScene.java`** - the re-roll path clears the date gate and restores the previous event
  if `createEvent()` still returns null.
- **`scene/EventScene.java`** - tutorial Coin refund clears `enteredWithCoinItem` (one refund per
  entry, not per losable round); the WIN nudge moved off `GameHUD.addNotification` onto a dialog via
  new `pendingWinNudge` / `showPendingWinNudgeDialog()`.
- **`scene/DuelScene.java`** - new `anteAlreadyRecovered` list stops a Buy Back followed by a Bronze
  Coin ransom duplicating the same ante card.
- **`player/AdventurePlayer.java`** - new `clearSuppressDefeatGoldLoss()`.
- **`stage/WorldStage.java`** - the Capitol-defense loss branch clears that flag before ending the
  run; it never reaches `defeated()`, which is what normally consumes it.
- **`data/ArmoryRarityData.java`** - javadoc records that `armoryRarityGatingEnabled` is inert
  unless `editionProgressionEnabled` is also true.
- **`standalone-packaging/build_standalone.py`** - the stale-jar guard now walks
  `forge-gui-mobile-dev`, the module whose build it instructs you to re-run.
- **`forge-gui-android/pom.xml`** - `tfr.version` 1.03 -> 1.04, `manifestVersionCode` 10300 -> 10400.

## Round 79 (2026-09-01) - Skip Tutorial dialog fix

- **No engine files changed.** The one code-adjacent fix is data: the plane's own
  `world/quests.json` (quest 28's skip option restructured so its text renders before its actions
  fire). Recorded here only to state that explicitly - the round's other work is documentation.

## Round 80 (2026-09-01) - dialog soft-lock, central dialog wrapping, #92 tier gate

- **`scene/MenuScene.java`** - `hideDialog()` now unwinds EVERY `UIScene.dialogs` entry for the
  shared Dialog instance plus the matching `possibleSelectionStack` frame, with a `[TFR-Dialog]`
  log line. It previously only faded the actor and cleared listeners, leaving a stack entry that
  `UIScene.removeDialog()` would later `show(stage)` again - resurrecting an emptied, buttonless,
  MODAL, immovable window that nothing could dismiss (user-reported hard-quit soft-lock).
  `showDialog(Array<DialogData>)` gained a guard refusing to re-show an instance that is no longer
  on the stack. **This leak predates the mod's own dialogs** - NewGameScene and
  EventScene.validateDeck() leak identically and are fixed by the same change.
- **`scene/UIScene.java`** - `createGenericDialog()` measures its body label and, only when it
  overflows, sets wrap and an explicit cell width; new `dialogBodyMaxWidth()` derives that cap from
  the live viewport (480 landscape / 270 portrait) rather than a constant. Fixes 10 at-risk call
  sites across StartScene, SettingsScene, InventoryScene, ArenaScene, RewardScene,
  WorldStandingsScene and EventScene; short one-line confirms lay out unchanged.
- **`util/EconomyBuildings.java`** - new `FLAT_TOWN_SHOP_TIERS` static tier table + its
  `buildFlatTownShopTiers()` generator and `auditFlatTownTierFallback()` drift check;
  `shopTierOf()` now consults slot pools -> static table -> `globalShopTiers` in that order, and
  logs once per unrecognised shop name. The static table deliberately outranks the accumulator,
  which is visit-order dependent (`putIfAbsent`).
- **`stage/MapStage.java`** - calls `EconomyBuildings.auditFlatTownTierFallback()` once per map
  load, after the layer loop so the pools describe the whole file.

## Round 81 (2026-09-01) - Inn tutorial messaging and Coin refund threshold

- **`scene/EventScene.java`** - the tutorial win nudge is now a FINAL-ROUND nudge that fires on a
  win or a loss (`pendingWinNudge` -> `pendingFinalRoundNudge`, `showPendingWinNudgeDialog` ->
  `showPendingFinalRoundDialog`), gated by new `isFinalRound()`. The Coin refund no longer uses a
  hardcoded `currentRound < 3`: new `coinRewardWinThreshold()` reads the lowest `minWins` of any
  reward tier whose `itemRewards` names a Coin (Draft 2, Sealed 2, Jumpstart none) and
  `hasEarnedCoinFromEvent()` compares it to `matchesWon`, so the rule is keyed on wins rather than
  rounds and is therefore correct under RoundRobin as well as SingleElimination. The refund
  suppresses the wrap-up when both would fire in one round.
- *(The quest-74 prologue reword lives in the plane's own `world/quests.json` - no engine file.)*

## Round 82 (2026-09-01) - Inn Coin refund diagnostics and one-time messaging

- **`scene/EventScene.java`** - new `[TFR-InnRefund]` line on every tournament loss, naming all
  three refund gates (paidWithCoin / alreadyEarnedCoin / tutorialQuestActive) plus round, wins and
  the event's coin-reward threshold. The refund dialog copy now states the net is one-time, and
  `innTutorialQuestActive()` carries a javadoc explaining why the quest gate must not be removed.
- *(Quest 74's prologue reword is in the plane's own `world/quests.json` - no engine file.)*
- **`CLAUDE.md`** (not an engine file; tracked here for completeness) - new "Release rule: take the
  upstream engine update FIRST" section ahead of Build/toolchain.

## New folder (2026-09-02) - dev-tools/save-editing/

- **`dev-tools/save-editing/`** (NEW, outside the plane folder, hence recorded here) - `README.md`,
  `Inspect.java` (read-only save dump: player, deck slots, full collection with counts),
  `BuildDecks2.java` (writes decklists into deck slots; dry-run by default, takes its own .bak),
  `DumpSave.java` (lower-level structural dump). Not part of any build - these are compiled ad hoc
  against the shipped game jar. Moved out of an ephemeral session scratchpad because the method had
  already been lost once that way; see MOD_CHANGELOG's "Technique: editing a save file".

## Round 83 (2026-09-02) - New Game+ and Arena-coin diagnostics

- **`player/AdventurePlayer.java`** - the `[TFR-NewGamePlus] reset done` summary now prints all nine
  fields the round-74 audit found leaking, each with its POST-reset value (`SET(LEAK)` if `blessing`
  survived), so one grep proves or disproves a New Game+ reset.
- **`scene/ArenaScene.java`** - new `[TFR-ArenaCoin]` lines at the round-win note, the bracket
  payout (paid vs mark-already-gone, plus an explicit "nothing owed" line) and the 0-rounds-won
  branch that drops pending notes unpaid.
- **`CLAUDE.md`, `ANDROID_RELEASE.md`** (not engine files; tracked for completeness) - the Android
  release build leaves `forge-game`/`forge-core` `target/classes` partial and the next desktop
  compile fails in untouched files; `mvn -pl forge-gui-mobile -am clean compile -DskipTests` is now
  the mandatory final step of every Android release. Round 81's concurrent-Maven diagnosis corrected.

## Round 86 (2026-09-02) - post-merge review/research fixes

- **`world/World.java`** - `generateNew()` resets `fogOfWarStage2Revealed` (new-run reveal re-armed).
- **`util/DungeonRotation.java`** (mod-new) - `resetSessionState()`.
- **`util/TerritoryControl.java`** (mod-new) - `clearPendingCapitolDefense()`; `[TFR-MageCap]` line
  de-duplicated via `lastMageCapLine`.
- **`stage/WorldStage.java`** - `clearCache()` calls both resets; `triggerGameLost()` clears the
  Bronze Coin defeat-gold suppression for all loss paths.
- **`player/AdventurePlayer.java`** - `[TFR-NewGamePlus]` label fix only.
- **`standalone-packaging/build_standalone.py`** (not engine; deploy gate) - daily-stamp guard,
  early launcher checks, `--allow-base-mismatch`.

## Round 87 (2026-09-02) - life-total diagnostics

- **`player/AdventurePlayer.java`** - `logLife()` helper; `[TFR-Life]` at every life/maxLife
  mutation and on load. Diagnostic only, no behavior change.

## Round 88 (2026-09-03) - multi-slot research, War town assault

- **`forge-game/.../player/RegisteredPlayer.java`** - `extraCardsOnBattlefieldTapped` +
  `getCardsOnBattlefieldTapped()` / `addExtraCardsOnBattlefieldTapped()`.
- **`forge-game/.../player/Player.java`** - `initVariantsZones()` places the tapped list and
  `setTapped(true)` on each (before the stock untapped loop).
- **`data/EffectData.java`** - `startBattleWithCardTapped`, copy ctor, `startBattleWithCardsTapped()`;
  the resolver is now a shared private `resolveCards()`.
- **`scene/DuelScene.java`** - `addEffects()` plumbs the tapped list.
- **`data/ConfigData.java`** - `warTownAssaultEnabled` (default false; on in the plane config).
- **`data/TuningData.java`** - `researchDays`, `researchShardCost`.
- **`player/AdventurePlayer.java`** - research map (see MOD_CHANGELOG), save key `researchInProgressList`.
- **`scene/ResearchScene.java`** (mod-new) - multi-edition list, tunable cost.
- **`stage/WorldStage.java`** - Attack button in `showEntryBarredDialog`, `startTownAssault()`,
  `[TFR-TownAssault]` in `setWinner()`.
- **`util/TerritoryControl.java`** (mod-new) - `pickRandomRoamer()`, `basicLandFor()`.

## Round 90 (2026-09-03) - serialVersionUID pins (save-integrity fix)

- **`data/EffectData.java`** - `serialVersionUID = -5573686949131910962L` (the v1.04 derived value;
  round 88's added field had changed it and wiped inventories on load).
- **`data/PointOfInterestData.java`, `data/WorldData.java`, `data/BiomeData.java`,
  `player/AdventurePlayer.java`, `pointofintrest/PointOfInterest.java`,
  `util/AdventureEventController.java`, `util/AdventureQuestController.java`,
  `world/BiomeTexture.java`, `world/WorldSaveHeader.java`** - explicit `serialVersionUID` pinned at
  each class's current derived value (all unchanged since v1.04). Upstream merges may re-touch
  these files; keep the pins.

## Round 91 (2026-09-03) - town assault capture

- **`util/TownRestoration.java`** (mod-new) - `captureTownForPlayer()`.
- **`util/TerritoryControl.java`** (mod-new) - `matchingWasteData()` package-private.
- **`stage/WorldStage.java`** - `townAssaultPoi`/`townAssaultColor`; `setWinner()` win path calls
  the capture; `startTownAssault()` records the POI.

## Round 92 (2026-09-03) - AI guard dots

- **`pointofintrest/PointOfInterestChanges.java`** - `aiGuardLevel` / `aiHeldSinceDay` (+ save/load keys, accessors).
- **`stage/PointOfInterestMapSprite.java`** - `drawGuardIndicator()` draws AI dots (`aiGuardDots()`).
- **`util/TerritoryControl.java`** (mod-new) - `updateAiTownGuardLevels()` from `processDaysPassed()`,
  tier-filtered `pickRandomRoamer()`, `AI_GUARD_MAX_LEVEL`.
- **`stage/WorldStage.java`** - `startTownAssault()` maps guard level -> defender tier / lands.
- **`data/TuningData.java`** - `aiTownGuardDaysPerLevel`.

## Round 93 (2026-09-03) - town assault weekly cooldown

- **`pointofintrest/PointOfInterestChanges.java`** - `aiLastAssaultDay` (+ save/load key, accessors).
- **`util/TerritoryControl.java`** (mod-new) - `assaultCooldownDaysLeft()`, `recordAssault()`.
- **`stage/WorldStage.java`** - `showEntryBarredDialog()` gates Attack on the cooldown and shows the
  remaining days; `startTownAssault()` records the attempt.
- **`data/TuningData.java`** - `aiTownAssaultCooldownDays`.

## Round 94 (2026-09-03) - assault defender rules

- **`util/ColorReputation.java`** (mod-new) - `applyTownAssaultPenalty()`.
- **`util/TerritoryControl.java`** (mod-new) - kill-decay weighted `pickRandomRoamer`, `difficultyIndex()`,
  `dispatchRetaliation()`.
- **`util/TownRestoration.java`** (mod-new) - capture penalty + retaliation in `captureTownForPlayer`.
- **`stage/WorldStage.java`** - defender life factor and attack penalty in `startTownAssault`.
- **`data/TuningData.java`** - `townDefenderLifeFactorByDifficulty`, `townAssaultReputationPenalty`,
  `townCaptureReputationPenalty`.

## Round 95 (2026-09-03) - Center Towns

- **`world/World.java`** - `starTownTiles` (+ save/load/reset), `recordStarTowns()` before
  `DungeonRotation.initializeNewWorld`, campfire->star edges added to the road pass.
- **`util/TerritoryControl.java`** (mod-new) - `checkStarTownLoss()`, `starTownOwner()`.
- **`data/TuningData.java`** - `starTownsLossCount`.

## Round 96 (2026-09-03) - star rim roads

- **`world/World.java`** - road pass adds the ten pairwise Center Town edges next to the five spokes.

## Round 97 (2026-09-03) - Center Towns fixes

- **`world/World.java`** - placement-loop exclusion (`isOrdinaryTownData`, `starTownExclusionRadius`);
  minimap draws Center Town art at 32x32.
- **`util/TownRestoration.java`** (mod-new) - star towns seeded first in `seedFunctioningNeutralTowns`;
  `getBrokenTownSprite` skips them.
- **`util/TerritoryControl.java`** (mod-new) - `onMageArrived` re-flags a reverted star town neutral-seeded.
- **`data/TuningData.java`** - `starTownExclusionRadiusTiles`.

## Round 98 (2026-09-03) - spacing, road trees, water-preserving repaint

- **`world/World.java`** - `tooCloseToPlacedTown` + placement reject; per-capital road tree before the
  nearest-neighbor pass (which now skips tree-covered towns as sources); `repaintBiomeAroundTown`
  skips water tiles.
- **`util/TerritoryControl.java`** (mod-new) - TEST-ONLY Center Town targeting override before the roll.
- **`data/TuningData.java`** - `townMinSpacingTiles`, `debugStarTownTargetChance` (TEST ONLY).

## Round 99 (2026-09-03) - Ring Towns, roads, AI guard fights, 1v2

- **`world/World.java`** - nearest-neighbor road pass restored and thinned (round-98 tree removed);
  `ringTargetDays` (+ save/load/reset); `roadConnectedTownIds` flood fill.
- **`util/TerritoryControl.java`** (mod-new) - ring cooldown filter + weight bonus + targeting record;
  `isRingTown`; `resolveAiGuardDefense` in the AI-vs-AI branch; no sacking of Ring Towns;
  `connectCapturedTownByRoad` targets only seat-connected towns; TEST override removed.
- **`stage/WorldStage.java`** - `entryBarredColor` challenges at AI-held Ring Towns; gate text;
  `startTownAssault` seats a second defender (`nextEnemy`, team 1) at Ring Towns.
- **`data/TuningData.java`** - `initialTownRoadSkipFraction`, `ringTownTargetCooldownDays`,
  `ringTownTargetWeightBonus`, `aiTownGuardDefenseEnabled`, `aiGuardTwoLandPowerFactor`;
  `debugStarTownTargetChance` removed.

## Round 100 (2026-09-03) - road links, capital assaults, victory, Ring life, perf, portrait layout

- **`world/World.java`** - degree-capped nearest-neighbor pass + rescue pass; `capitolLostColors`,
  `ringVisitedTiles` (+ save/load/reset).
- **`util/TerritoryControl.java`** (mod-new) - mage cap halved for capital-lost colors; `pickRandomRoamer`
  exclusion overload; `checkPlayerVictory`; `[TFR-Perf]` timings.
- **`util/TownRestoration.java`** (mod-new) - capital-lost marking, victory check, `updateRingLifeBonus`.
- **`stage/WorldStage.java`** - capital toll dialog Attack; `startTownAssault` capital/1v2/distinct defenders;
  `triggerGameWon`; Ring visit hook in `loadPOI`.
- **`player/AdventurePlayer.java`** - `ringLifeBonus` + `applyRingLifeBonus` (serialVersionUID pinned, additive field).
- **`world/WorldSave.java`** - ring life recompute on load.
- **`scene/ResearchScene.java`, `util/EconomyBuildings.java`, `scene/WorldStandingsScene.java`** - portrait sizing.
- **`data/TuningData.java`** - `townMaxRoadLinks`.

## Round 101 (2026-09-03) - Ring gifts / start with nothing

- **`data/DialogData.java`** - `ActionData.grantRingGift` (serialVersionUID pinned; additive).
- **`scene/MenuScene.java`, `util/MapDialog.java`** - execute `grantRingGift` next to the resource actions.
- **`player/AdventurePlayer.java`** - `grantRingGift(kind)`, creation grants nothing when `ringGiftStart`.
- **`data/ConfigData.java`** - `ringGiftStart`.

## Round 102 (2026-09-04) - gifts as rewards, nav filter, victory by castles

- **`player/AdventurePlayer.java`** - `grantRingGift` builds RewardData from the config difficulty and opens the RewardScene.
- **`data/AdventureQuestStage.java`** - `navPOIFilter` case "tagged".
- **`util/TerritoryControl.java`** (mod-new) - victory uses `getDefeatedColorCount`; checked in `defeatColor` too.
- **`world/World.java`** - `tooCloseToPlacedTown` per-neighbor minimum (Ring Cities 14 tiles).
- **`data/TuningData.java`** - `ringCityTownExclusionTiles`.

## Round 103 (2026-09-04) - skip-intro direct grants

- **`player/AdventurePlayer.java`** - `grantRingGift("all")` grants directly + marks the Ring visited.

## Round 105 (2026-09-04) - end-game splashes, Ring entry flags

- **`stage/WorldStage.java`** - `showEndSplash`/`hideEndSplash`, `showGameWonDialog` + deferral in `enter()`.
- **`scene/TileMapScene.java`** - `enteredRingCity<N>` character flags on Ring City entry.
- **`util/TownRestoration.java`, `player/AdventurePlayer.java`** - black notification text.

## Round 106 (2026-09-04) - Ring City layouts/shops

- **`scene/TileMapScene.java`** - `resolveMapPath` (Ring City layouts); **`stage/MapStage.java`** uses it for map resets.
- **`character/ShopActor.java`** - Ring shop price multiplier; **`scene/RewardScene.java`** - `restockPriceNow`.
- **`util/EditionProgression.java`** - Ring shops bypass the edition restriction.
- **`util/EconomyBuildings.java`** - `isRingShop` (special); **`player/AdventurePlayer.java`** - no Ring blueprints.
- **`player/AdventurePlayer.java`** - round 112: `ringLifeBonus` saved/loaded (-1 = pre-fix save, adopted without re-adding); `addMaxLife` clamps life to [1, max] on negative amounts.
- **`player/AdventurePlayer.java`** - round 113: `grantRingGift` NG+ handling (no coin re-grant, no duplicate start items); **`stage/MapSprite.java`** - magnifier for `sideboss*` POI types.
- **`data/AdventureQuestStage.java`** - round 115: `survivingTown` / `ruinedTown` nav filters exclude Ring Cities; **`player/AdventurePlayer.java`** - `addGold` / `addWood` / `addStone` play `CoinsDrop` on positive amounts; **`util/ResourceSpawns.java`** - the explicit wood/stone sound calls removed (now inside the adds).
- **`scene/TileMapScene.java`** - round 118: the ruined-town flag skips Ring Cities too; **`util/AdventureEventController.java`** - Jumpstart roll gated on the `jumpstartPlayed` flag; **`data/AdventureEventData.java`** - `startEvent()` sets `jumpstartPlayed` for Jumpstart events.
- **`scene/UIScene.java`** - round 120: `showDialog()` ends with `toFront()`; `act()` re-asserts the top dialog's z-order each frame (RewardScene re-adds card actors above open dialogs).
- **`util/EconomyBuildings.java`** - round 121: the Financial submenu offers the Trader only while neither TRADER nor EXCHANGE is built (the Exchange upgrade clears the Trader flag); the Trader build option carries both not-built conditions.
- **`world/World.java`** - round 121: new `flashArea(cx, cy, radius, cb)` - flashes every tile in the circle bright for `TEMPORARY_REVEAL_SECONDS` (explored or not) after a `revealArea()` for permanence.
- **`stage/WorldBackground.java`** - round 121: `flashDiscoveryAround(poi)` replays the discovery burst on demand with the POI's discovery radius; `isTownLikePoi` is package-private now.
- **`stage/WorldStage.java`** - round 121: `flashDiscoveryAround(poi)` delegate to the background.
- **`stage/MapStage.java`** - round 121: `exitDungeon()` fires the town-exit flash for town/capital/castle maps when the player was not defeated.
- **`util/TerritoryControl.java`** - `ringPullDivisor`; **`stage/WorldStage.java`** - no lands at Ring assaults.
- **`util/MapDialog.java`** - option pane height from the HUD stage.

## Round 107 (2026-09-04) - capture quests, hero enemies

- **`data/AdventureQuestData.java`** - `giverColor`, `requiredColorStatus` (UID pinned 1L; additive).
- **`util/AdventureQuestController.java`** - Partner gate in the board template selection.
- **`util/TownRestoration.java`** - Ring Cities: 0 broken slots, `isPermanentlyBrokenShop` guard; `capturedFrom_<color>` flag.
- **`scene/TileMapScene.java`** - drops stale broken-slot flags in Ring Cities.
- **`scene/TileMapScene.java`** - round 111: `enteredSurvivingTown` is not set for Ring Cities (quest 30 pre-completion fix).


## Round 122 (2026-09-05) - DungeonClear log gate, cave icon sets, Rally rune

- **`stage/MapStage.java`** - `clearDungeonIfEmptied()` prints its `[TFR-DungeonClear]` line only when `DungeonRotation.isRotatableData()` holds (towns, the Capitol and castles no longer claim a despawn).
- **`pointofintrest/PointOfInterest.java`** - new private static `spreadZeroSpriteIndex()`: a zero `spriteIndex` against a multi-sprite set is re-derived from the POI's name + position, in the constructor and in `load()`; the serialVersionUID pin is unchanged.
- **`world/World.java`** - `rallyLastTargetId` (String: `store`d when set, `containsKey`-guarded read, reset with the Territory Control state) + getter/setter.
- **`util/TerritoryControl.java`** (mod-new) - `playerTownsUnderAttack()`, `nextRallyTarget(world, targets)`.
- **`stage/ConsoleCommandInterpreter.java`** - `teleport rally` command (the Rally rune's `commandOnUse`; refunds the rune's shards and posts a notification when no player town is under attack).

## Round 123 (2026-09-05) - code-review fixes, Rally rune quest reward

Review: `docs/review/2026-09-05-code-review.md` (finding ids below). Every change is a few lines with a
`round 123 review S…` comment at the site.

- **`util/SaveFileData.java`** - `serialVersionUID = 2370928267361276519L` pinned (S1-4; the derived v1.05 value).
- **`data/DialogData.java`** - `ActionData.QuestFlag.serialVersionUID = -3808510202986416615L` pinned (S1-3).
- **`data/AdventureEventData.java`** - `AdventureEventHuman.serialVersionUID = -6819102903640880265L` pinned (S1-3).
- **`world/WorldSave.java`** - `save()` also catches `RuntimeException`: restores the `.old` backup, `[TFR-Save]` line, error dialog (S1-2).
- **`world/World.java`** - `generateNew()` and `rebakeMinimapAfterTerritoryControl()` dispose the previous `biomeImage` (S2-1); `getBiomeSprite()` now returns a caller-owned pixmap (new `copyTile()` for the shared fog tile; `hazeTile()`'s input disposed); `generateBiomeSprite()`'s map-edge case draws the shared tile into the already-allocated pixmap instead of leaking it and returning the shared one (S2-2).
- **`stage/WorldBackground.java`** - `getChunkTexture()` and `onTileRevealed()` dispose each tile pixmap after `draw()` (S2-2).
- **`world/BiomeStructure.java`** (upstream file) - WFC failure branch loops over `chunkWidth`/`chunkHeight`, not `dataMap.length` (S2-7).
- **`util/TerritoryControl.java`** (mod-new) - `onMageArrived()` removes `TOWN_RESTORED_FLAG` from the old-id changes when a player town is captured, sacked or reverted (`[TFR-Ownership]`, S2-3); new `resetSessionState()` clears the pull-source fingerprints, re-contest days and neutral-defense tally, called from `neutralizeAfterGeneration()` (S2-5).
- **`stage/WorldStage.java`** - `clearCache()` calls `TerritoryControl.resetSessionState()` (S2-5).
- **`scene/DuelScene.java`** - new `chargeInGameManaShards(int)`: lowers the human seat's in-match mana shards so `GameEnd()`'s write-back keeps an Ante Re-roll charge (S3-1).
- **`forge-gui-mobile/src/forge/screens/match/MatchController.java`** - `revealAnteCards()` calls it after `takeShards(cost)` (S3-1).
- **`scene/TileMapScene.java`** - new `disposePreviousMap()` before both `TemplateTmxMapLoader().load(...)` calls (S4-1).
- **`stage/MapStage.java`** - `resetMapRecursive()` disposes each parsed map in a `finally` (S4-2).
- **Data** - `world/quests.json` quest 43 stage 2 epilogue (guard briefing + `grantRewards` "Rally rune"); `GUIDE.md`; `MOD_SCOPE.md` #104.
- **Tooling** - `dev-tools/validate_plane_data.py` + `validate_plane_data_stage_fields.txt` (plane data validator); `docs/review/` (the review, data report, Phase 0 logs).

## Round 124 (2026-09-06) - Torch pulse, saved-item refresh, review data fixes

- **`data/TuningData.java`** - `torchPulseMultiplier` (3), `torchPulseSeconds` (2), `torchPulseMaxRadiusTiles` (24); mirrored in `config tables/settings.json`.
- **`world/World.java`** - `temporarilyReveal(x, y, seconds)` and `flashArea(cx, cy, radius, seconds, callback)` overloads (the 4-arg versions delegate with the 3 s discovery constant); `isFogOfWarEnabled()` made public.
- **`stage/WorldBackground.java`** - `pulseVision(multiplier, seconds, maxRadiusTiles)`: the Torch flare (`[TFR-TorchPulse]`).
- **`stage/WorldStage.java`** - `pulseVision(...)` bridge.
- **`stage/ConsoleCommandInterpreter.java`** - `torch pulse` command (refunds the shard off the world map / with fog off).
- **`player/AdventurePlayer.java`** - `refreshItemDefinitionsFromCatalog()` called from `load()`: saved ItemData objects take the catalog's definition fields (`[TFR-ItemRefresh]`).
- **Data** - `items.json` (Torch, Grand Torch: usableOnWorldMap, shardsNeeded 1, commandOnUse, description), `enemies.json` (56 dangling item rewards removed, Slobad `card`, 3 colors upper-cased), `shops.json` (Horror shop added, Random sign -> CardShop), `maps/map/skep/skep_outer.tmx`, `temple_of_liliana/bog.tmx`, `forest.tmx`, `fort/fort_colorless_3_human.tmx`, `fort/fort_white_4_farm.tmx` (enemy names), `towns/plains_town_generic.tmx` (object 51 shop lists).
- **Tooling** - `dev-tools/validate_plane_data.py` knows the `torch` command root and the three tuning keys.

## Round 125 (2026-09-06) - Arena decks and tiers, generated caves, mage base

- **`scene/DuelScene.java`** - the `arenaBattleChallenge` flag, its `isArena && isHardorInsaneDifficulty()` assignment and the branch that dealt Arena fighters a random genetic-AI deck are removed; Arena fighters take the same final branch as roaming enemies.
- **`scene/ArenaScene.java`** - `loadArenaData()` tier-weighted seat fill (Adept 50 / Master 35 / Archmage 15, Common excluded) gated on AI-capital arenas and level-1 player-owned arenas in Regular mode; new `pickTierWeighted()`, `isAiCapitalArena()`; `[TFR-ArenaTier]` per bracket.
- **`data/TuningData.java`** - `baseAttackingMagesPerColor` default 3 -> 2; `util/TerritoryControl.java` comment only. Mirrored in `config tables/settings.json`.
- **Data** - 78 new maps `maps/map/cave/cave_<biome>_01..13.tmx`; `points_of_interest.json` +78 `Cave<L>Gen<nn>` entries; `biomes/<biome>.json` `pointsOfInterest` +13 each; `GUIDE.md`.
- **Tooling** - `dev-tools/gen_caves.py` (the generator; regenerates the same files from the same seeds) + `dev-tools/gen_caves_manifest.txt`; `dev-tools/save-editing/WriteDecks.java` backup name = first free `.prededit<N>.bak`; deck lists v6 in `dev-tools/save-editing/`.

## Round 126 (2026-09-06) - pending match result cancelled on load, mis-sized sprites

- **`stage/GameStage.java`** - `pendingResultTask` + `scheduleResultTask()` (the match-result / defeat `Timer` tasks go through it), `cancelPendingActions()` (`[TFR-LoadReset]`); `resetPlayerLocation()` and `defeatedFromBoss()` use `scheduleResultTask()`.
- **`stage/MapStage.java`** - win-path `Timer` via `scheduleResultTask()`; `cancelPendingActions()` override (currentMob, enemy freeze, loading-match flag).
- **`stage/WorldStage.java`** - win-path `Timer` via `scheduleResultTask()`; `cancelPendingActions()` override (currentMob, Capitol-defense / town-assault flags); `clearCache()` cancels both stages' pending actions on every load and new game.
- **Data** - `enemies.json`: Arcane Golem `scale` 3 -> 0.5; Shorikai, Dementia Beast, Blech `scale` 0.5.

## Round 159 (2026-09-09) - enemy tier size cue

- **`character/CharacterSprite.java`** - `draw()` now sizes the frame at `atlasRegionSize x EnemyData.scale x TuningData.tierScale(tier)` instead of `atlasRegionSize x scale`. Tier is a separate multiplier on purpose: `scale` carries the artist's per-creature intent and folding tier into it would destroy that. Neutral (all tiers 1.0) restores stock behaviour exactly.
- **`data/TuningData.java`** - `enemyTierScaleCommon/Uncommon/Rare/Mythic` + `tierScale(String)`, returning 1.0 for any unrecognised tier so a stock plane is never resized.
- **Data** - `enemies.json`: 10 sub-tile `scale` values on humanoid/large-monster art normalised to 1.0 (Zo-Zu the Punisher 0.3, Arabella 0.5, Syr Ginger 0.5, Aminatou 0.6, Devil of Tibalt 0.7, Bria 0.8, Horror of Tibalt 0.8, Geistmage 0.9, Heart-Piercer Manticore 0.9, Lion 0.9). The other 17 sub-1.0 entries are deliberately small creatures and were left alone.

## Round 160 (2026-09-09) - tile-anchored tier cue, guard cue, code-review fixes

- **`character/CharacterSprite.java`** - `draw()` multiplies by `TuningData.tierSizeMultiplier(tier, frameHeight x scale)` instead of `tierScale(tier)`, null-guards `getData()`, and a new `tierCue` field + `setTierCue(String)` lets a non-EnemySprite (the roaming guards) carry the cue. Neutral settings still restore stock behaviour exactly.
- **`player/AdventurePlayer.java`** - `dropUngrantedSlots()` tests `"Left2"`/`"Right2"` explicitly instead of `endsWith("2")` (which also matched the stock `Ability2` slot and un-equipped every item placed there) and loops until stable; `removeItem(ItemData)` calls it after un-equipping; `resetForNewGamePlus()` zeroes the roaming guards' day fields. Mod-only logic on a stock file.
- **`scene/DuelScene.java`** - `initDuels()` recomputes `chaosBattle` and clears the extras lists again (the v1.08 tail, which round 145 had moved into `useGuardLoadout`); `addEffects()` logs `[TFR-DuelEffects]` per seat. Both inside mod-touched regions; upstream's `initDuels` tail is byte-identical to v1.08 again.
- **`stage/WorldStage.java`** - `simulateGuardDuel()` scales the mage's life like DuelScene does (enemyLifeFactor + terrain), `startGuardDuel()` positions the foe clone on the mage's tile. Mod-added methods.
- **`util/CardUtil.java`** - `remapToEditionList()`'s in-list early return now also requires an allowed rarity. Mod-added method.
- **`scene/InventoryScene.java`** - `equip()` re-selects the item's rebuilt button via new `reselect(ItemData)`. Small edit to a stock method.
- **`stage/GameHUD.java`** - `updateAbility()` applies the round-158 in-map hiding when it rebuilds the buttons. Small edit to a stock method.
- `data/TuningData.java`, `util/EconomyBuildings.java`, `util/RoamingGuardRuntime.java`, `util/RoamingGuards.java` are mod-added files (no merge burden); see MOD_CHANGELOG.

## Round 161 (2026-09-09) - agent bridge hooks

- **`forge/Forge.java`** - `render()` calls `AgentBridge.startIfConfigured()` first thing (a static boolean check after the first frame) and `AgentBridge.afterRender()` at both exits (frame counter; screenshot capture point). Three lines. The start must be here rather than in Adventure mode because the splash's mode selector precedes Adventure mode; the end-of-frame hook must be here so screenshots work during matches.
- **`screens/match/MatchController.java`** - `revealAnteCards()` returns before its reveal and Re-roll prompts when `AgentBridge.aiPilotsPlayer()` (three lines inside the mod's own override).
- **`scene/DuelScene.java`** - the player's LobbyPlayer is the AI one when `aiControlsPlayerSide || AgentBridge.aiPilotsPlayer()`; nothing else in the seat construction changes, so with the bridge's auto-battle the fight stays the player's (equipment, ante, rewards, statistics).
- **`stage/GameHUD.java`** - `addNotification(String, boolean)` first hands the text to `AgentBridge.noteNotification()` (a no-op when the bridge is off).
- New mod-added files, no merge burden: `agent/*`, `stage/AgentStageAccess.java` (reads `WorldStage.enemies`, `MapStage.actors`, `MapStage.navMapSize`), `scene/AgentSceneAccess.java` (reads `UIScene.stage` / `HudScene.stage`, starts a new game).

## Round 162 (2026-09-10) - minimap overlays, JSON Windows

- **`scene/MapViewScene.java`** (stock file; the overlay views are mod-added from round 156 on) - the Attacks view's lines moved out of `mageMarkers` into their own lists (`attackEnds`/`attackColors`/`attackLines`) with `clearAttacks()` and `layoutAttacks()`; the overlay labels keep world anchors (`detailAnchors`) and `layoutDetails()` re-lays them on zoom; the five views' clear blocks became `clearDetails(); clearAttacks();`; `done()` clears the anchors and lines; `zoomIn()/zoomOut()` call `layoutDetails()` + `layoutAttacks()` after `resolveLabelOverlaps()`, which now skips the anchored labels; `import com.badlogic.gdx.graphics.Color`. Merge burden: a handful of one-line insertions in stock methods at the "after the labels are cleared / after the overlap pass" spots.
- **`util/UIActor.java`** (stock file) - `readWindowProperties()` sets every layout-built `Window` `Touchable.disabled` after the existing `setMovable(false)`: the Window's capture listener `toFront()`s it over its flat siblings on any click. One line.

## Round 163 (2026-09-10) - Armory storage and guard equipment

- **`player/AdventurePlayer.java`** - new `armoryStorage` list + `getArmoryStorage()`; cleared in `clear()`; saved as `storeObject("armoryStorage", ItemData[])` next to the inventory and read back `containsKey`-guarded before the round-124 catalog refresh; that refresh now walks the inventory, the storage and every roaming guard's `equipment` (one combined list, loop body unchanged).
- **`scene/RewardScene.java`** - `storageButton` (same programmatic-button pattern as Destroy/Guards/Upgrade/Re-roll), positioned one row below Done, reset with the other mod buttons, shown under the `armoryFeatures` gate; `promptArmoryStorage()` / `refreshStorageButton()`.
- **`scene/DuelScene.java`** - `guardEffects` field cleared by `initDuels()`; `useGuardLoadout(Deck, int, Array<EffectData>)` (was two-arg; single caller in WorldStage); in `enter()`, the guard's effects are added to the seat lists right before the blessings block; `addEffects()` delegates to a new `public static applyEffects()` with the unchanged body.
- **`stage/WorldStage.java`** - `startGuardDuel` passes `ArmoryStorage.effectsOf(guard)`; `simulateGuardDuel` uses the new hooked `runBatch` overload and logs the gear.
- Mod-added files, no merge burden: `util/ArmoryStorage.java`, `util/ArmoryStorageUI.java` (new); `util/DeckTesterSimulator.java` (hooked overload; the old signature delegates), `util/RoamingGuards.java`, `util/RoamingGuardRuntime.java`, `util/RoamingGuardUI.java`, `util/EconomyBuildings.java` (one line), `data/RoamingGuardData.java`.

## Round 164 (2026-09-10) - portrait pass

- **`scene/RewardScene.java`** - the seven programmatic shop-page buttons (mod-added in rounds 3-163) are placed by one new `placeModButton(button, row)` with a landscape branch (the old inline formula) and a portrait branch; the seven inline `setSize`/`setPosition` pairs are gone. No stock line changed.
- **`scene/MapViewScene.java`** - `names()` (mod-added view) checks `ui.findActor("attacks")` before handing the cycle to the Attacks view, so a plane using the stock `common/ui/map.json` closes the cycle at Names.

## Round 166 (2026-09-10) - release-blocker answers

- **`scene/DuelScene.java`** - the reputation block at the top of `afterGameEnd()` and the statistics block inside its `endRunnable` moved into two new `public static` methods, `recordReputation(EnemySprite)` and `recordStatistics(EnemySprite, String, boolean)`, with the callers' conditions left in place; `clearBlessing()` / `clearPartnerOverhealIfActive()` at the match end are now inside `if (!aiControlsPlayerSide)`. Same behaviour for every player-played duel.
- **`stage/MapStage.java`** - two lines in the dungeon defeat handler (before the `defeatDialog` branch): if the fallen enemy was this cave's champion placement, `CaveChampions.onChampionDefeated(...)`.
- **`stage/WorldStage.java`** - the mage-arrival branch reads `RoamingGuardRuntime.onArrival()` (FIGHT / PASS / WAIT; WAIT `continue`s) instead of the boolean `interceptOnArrival()`; `simulateGuardDuel()`'s result callback records reputation + statistics through DuelScene's new statics.
- **`scene/RewardScene.java`** - the storage button's visibility gains `armoryLevel >= 2` and a Capitol check on `TileMapScene.instance().rootPoint`.
- Mod-added files: `util/CaveChampions.java` (+`onChampionDefeated`), `util/WarChampions.java` (`injectFor` moves a present champion to the tail), `util/RoamingGuardRuntime.java` (`onArrival`, `Arrival`, `waitingMage`, gate hold in `onDuelFinished`, `reset` clears the wait), `util/RoamingGuardUI.java` (paged `openDeckPicker` / `openDeckReturn`, `DECK_PAGE`), `util/ArmoryStorage.java` (doc only).

## Round 167 (2026-09-10) - hotfix

- **`util/UIActor.java`** - `readWindowProperties()`: the round-162 `setTouchable(Touchable.disabled)` became `getCaptureListeners().clear()` - drops only the Window's toFront-on-touch capture listener; the window and the children scenes add into it stay touchable.

## Round 168 (2026-09-10) - the Armory screen

- **`scene/RewardScene.java`** - `promptArmoryStorage()` opens `ArmoryScene`; `enter()` refreshes the Storage button's count after the round-146 re-open check. Two lines.
- New mod-added: `scene/ArmoryScene.java` (a UIScene shaped like InventoryScene, two grids), `ui/armory.json`, `ui/armory_portrait.json`.

## Round 170 (2026-09-10) - storage ownership

- **`scene/AdventureDeckEditor.java`** - the sketchbook scan iterates the pack plus `getArmoryStorage()` (three lines).
- **`player/AdventurePlayer.java`** - `hasItem()` / `countItem()` also count `armoryStorage`; `removeItem(String)` falls back to it when the pack has no such item.

## Round 172 (2026-09-10) - no engine edits; the next merge sized

No stock file changed this round. Preview of the next upstream merge, for whoever does it: upstream `master` was 22
commits past our merge point `06a3c05731c` when checked (merge only to the commit the user's Forge_2 install is at -
round 165's content-probe method). 7 commits touch Java, 55 files; the big ones are #11833 "Update UIScene Backdrop
and Adventure DeckEditor Colors" (26 files) and #11816 "Fix auto-pass network crash and consolidate logic" (18). The
14 of those files this doc lists:

| File | Upstream | What to expect |
|---|---|---|
| `adventure/scene/UIScene.java` | +33/-39 | the backdrop code again - round 165's one conflict (shader backdrop vs the round-116 null guard) |
| `adventure/scene/AdventureDeckEditor.java` | +54/-3 | a backdrop constructor parameter + `drawBackground()` overrides; the first insertion (~line 46) is near our round-170 sketchbook scan (~line 110) |
| `adventure/world/WorldSave.java` | +14/-3 | `announceError` -> a new `finish()` in the load/save error paths - where review finding E4 lives, so E4 goes AFTER the merge |
| `adventure/util/UIActor.java` | +8/-28 | the `title_bg` shader drawable becomes a `ShaderDrawable`; our `readWindowProperties()` lines (rounds 162/167) are ~100 lines above |
| `forge/Forge.java` | +7 | an `isDisposed` guard and `safeDispose(world)` on dispose; our agent hooks are in `render()` |
| `adventure/scene/EventScene.java` | +6/-2 | `DeckEditScene.getInstance(TextureRegion)` replaces the no-argument form (removed upstream) |
| `adventure/scene/MapViewScene.java` | +5/-6 | `refreshMap()`'s minimap texture comes from `getNewMiniMapTexture()` |
| `adventure/world/World.java` | +4/-3 | `biomeImage` disposal |
| `forge/Adventure.java` | +4/-3 | `dispose()` early return |
| `screens/match/MatchController.java` | +4/-2 | yield-marker long-press; our ante hook is ~70 lines below |
| `adventure/scene/InnScene.java` | +3/-2 | `ShopScene.instance(TextureRegion)` replaces the no-argument form |
| `adventure/util/RewardActor.java` | +2/-2 | edition code on the rewards screen |
| `adventure/player/AdventurePlayer.java` | +1/-1 | `refreshEditor()` calls `getInstance(null)` - a method body, no save-format effect |
| `adventure/stage/WorldStage.java` | +1/-1 | the autosave overlay text |

Every call site of the two removed no-argument accessors is in a stock file upstream updates in the same merge
(`AdventurePlayer`, `DeckSelectScene`, `EventScene`, `InnScene`). No upstream change adds a field to a save-bound class.

## Round 173 (2026-09-10) - review G10 / S8 / G6 / S1 / S4

- **`scene/DuelScene.java`** - `enter()`: the player seat's `setManaShards(advPlayer.getShards())` gains `!aiControlsPlayerSide &&` (a spectated guard fight / the Deck Tester's watched AI-vs-AI no longer run on the player's purse). One condition in a mod-touched region.
- **`data/BiomeData.java`** - `getEnemy(float)` now delegates to a new `getEnemy(float, boolean withInjectedSpawns)`; the two mod-added injection lines (war champions, frontier spawns) become conditional on the flag. The stock body is otherwise unchanged. `serialVersionUID` is pinned, so the extra method cannot move the save format.
- **`stage/WorldStage.java`** - `save()`: after the enemy loop, the mage a roaming guard is fighting (`RoamingGuardRuntime.duellingMage()`) is appended to the same eight lists if it is not already listed; `load()`: `RoamingGuardRuntime.resolveInterruptedDuels(enemies)` in its own try just before the `globalTimer` read; `spawn()`'s `[TFR-Spawn]` block adds `[TFR-Frontier]` / `[TFR-WarChampion]` lines. All inside mod-touched regions.
- **`player/AdventurePlayer.java`** - `resetForNewGamePlus()`'s guard loop clears `inDuel` (one line, mod-only logic).
- Mod-added files, no merge burden: `util/TerritoryControl.java` (re-theme passes `false`), `util/FrontierSpawns.java` (clone moved to the tail, war-cast and player-biome guards), `util/ChestEvents.java` (heavyweights as group members), `data/RoamingGuardData.java` (`inDuel`), `util/RoamingGuards.java` (persists it), `util/RoamingGuardRuntime.java` (`duellingMage()`, the flag, `resolveInterruptedDuels()`).

## Round 174 (2026-09-10) - resource glyphs, the Duplicate card

- **`stage/WorldStage.java`** - both edits are inside mod-added methods: `showCapitalTollDialog()`'s text and Pay buttons use `[+Gold]`; `showChestDuplicateDialog()` shows the offered cards (`RewardActor`s in a row table, removed and disposed when the dialog is answered) and its buttons use `[+Shards]`; new private static `addDuplicateOffer()`.
- **`forge-gui-mobile-dev/pom.xml`** - the first `<resource>` (directory `${project.basedir}`, `**/title_bg_lq.png`-style includes) gains `<excludes><exclude>target/**</exclude></excludes>`: without it every non-clean build copied the previous build's `target/classes` copies into `target/classes/target/classes/...` (the v1.09 jar held 59 levels, 52.9 MB). Upstream's pom is identical and has the same latent bug - a candidate for an upstream PR. On a merge conflict here, keep the exclude.
- Mod-added files, no merge burden: `util/ChestEvents.java` (the gold chest notification), `scene/WorldStandingsScene.java` (the reputation explainer's toll).

## Round 175 (2026-09-10) - agent play, isolated

No stock file changed. `stage/AgentStageAccess.java` (mod-added) now writes `WorldStage.collidingPoint` - a PRIVATE
stock field - by reflection (`exemptPoiUnderPlayer()`), and reads it for the `[TFR-Agent] walk start:` diagnostic.
**On an upstream merge**: if WorldStage renames or removes `collidingPoint`, nothing fails to compile - the walk-start
line then reports `collidingPoint ?` and `exemptPoiUnderPlayer()` returns "failed: ...", and agent walks may walk back
into the town just left. It also leans on `TileMapScene.leave()` clearing the world player's collision height and
`GameHUD` restoring it (the reason the exemption is needed). `agent/WalkController.java` is mod-added.

## Round 176 (2026-09-11) - no engine edits

Client only (`dev-tools/agent/tfr_agent.py`: `settle` stops at a lost ante's Bronze Coin / Buy Back prompt).

## Round 177 (2026-09-11) - flat defeat gold, the Wasteland mix-in

- **`player/AdventurePlayer.java`** - `defeated()`'s gold branch (the non-Bronze-Coin `else`): a flat loss from
  `TuningData.defeatGoldLossFor(difficultyData.name)` when it is > 0 (`gold = max(0, gold - flat)`), the stock
  `gold - gold * goldLoss` otherwise; the `[TFR-DefeatGold]` line. Stock method, mod-touched region since the Bronze Coin
  (2026-08-29); on a merge keep the Bronze Coin branch first and this `else`.
- **`scene/NewGameScene.java`** - the Duels tab's `matchImpacts.text`: the gold-loss value is a `goldLossText` string
  (flat amount with the `[+Gold]` glyph, or the stock percentage). Stock line.
- **`stage/WorldStage.java`** - `handleMonsterSpawn()`'s player-territory colorless mix-in (mod-added block): biome
  name "waste" (fallback "colorless") and the chance from `TuningData.playerColorlessMixChance`.
- Mod-added files, no merge burden: `data/TuningData.java` (four `defeatGoldLoss*` ints, `defeatGoldLossFor()`,
  `playerColorlessMixChance`), `util/TerritoryControl.java` (`pickGrandmasterMage()` keeps roster names only),
  `util/ChestEvents.java` (`archmageCount(World)` counts the five color rosters).

## Round 178 (2026-09-11) - one size per rank, the race reward, the loading glyph

- **`data/EnemyData.java`** - two new booleans, `legend` and `keepSize`, and their copy-constructor lines (the
  `serialVersionUID` is pinned; new fields default false on old data).
- **`data/RewardData.java`** - new `raceEditions` boolean (copy constructor too) and a guard at the top of the 4-arg
  `generate()`: when set, generate from a copy whose `editions` are the player's race editions. Stock class, additions
  only; on a merge keep the guard ahead of stock's body.
- **`character/EnemySprite.java`** - `updateBoundingRect()`: `unfreezeRange` from the drawn size instead of `30 x scale`.
- **`character/MapActor.java`** - `getCenter()`: no second multiplication by `EnemyData.scale` (a stock bug: the width is
  already the drawn width). If upstream fixes it their own way, take theirs.
- **`scene/GameScene.java`** - `getAdventurePlayerLocation()`'s color-glyph switch gains `case "player" -> "[+tfr]"`.
- Mod-added files, no merge burden: `data/TuningData.java` (straight tier cue, new defaults, TILE_PX gone),
  `util/CaveChampions.java` / `util/FrontierSpawns.java` (read `legend`), `util/TerritoryControl.java` (mage scale
  override removed).

## Round 179 (2026-09-11) - no engine edits

Data and tooling only: 197 new enemies (`world/enemies.json`, biome rosters, capital arena pools, the generated caves,
`sprites/enemy/tfr/`, `decks/standard/tfr/`), `dev-tools/enemy_scale.py` (POSE_CAP), `dev-tools/agent/tfr_agent.py`
(`settle` collects loot through Done). No Java changed.

## Round 180 (2026-09-11) - loot restriction exemption

- **`character/EnemySprite.java`** - `getRewards()`, the mod-added Progressive Set Unlocks block: the exemption test is
  `SpawnTierWeighting.isExempt(data)` (boss or spawnRate <= 0) instead of "boss or any quest tag"; the EXEMPT log line
  prints spawnRate. Stock method, mod-added block (since 2026-08-12); on a merge keep the block after the deck pools.
- Mod-added, no merge burden: `util/SpawnTierWeighting.java` (comment on `isExempt()`).

## Round 181 (2026-09-11) - scripted map placements

- **`stage/MapStage.java`** - new private `isScriptedPlacement(EnemyData)` + `STORY_TAGS`; `prepareCaveChampion()`'s
  candidate test and `loadObjects()`'s content-filter / re-theme branch call it instead of "boss or any quest tag".
  Both call sites are mod-added code inside stock methods (rounds 139 and 2026-08-10/12); on a merge keep the helper
  with them.

## Round 182 (2026-09-11) - upstream merge @ 26d8aff8750 (the 09.11 daily)

30 commits / 178 files / 84 Java since `06a3c05731c`. Conflicts, and how each was resolved:
- **`forge/Forge.java`** `render()` - upstream's `isDisposed` early return first, then `AgentBridge.startIfConfigured()`.
- **`adventure/scene/EventScene.java`** - imports only (upstream's `TextureRegion` + our `Actor`).
- **`adventure/scene/InnScene.java`** `sell()` - our `isRuinedTown()` guard, then upstream's
  `ShopScene.instance(getUIBackground())` (the no-argument accessor is gone upstream).
- **`adventure/scene/MapViewScene.java`** - OUR `refreshMap()` kept (own `miniMapTexture`, rebuilt every refresh);
  upstream's `Assets.getNewMiniMapTexture()` is NOT used: it skips the upload when the Pixmap object is unchanged,
  and fog of war / territory repaint mutate that Pixmap in place. On the next merge, keep this unless upstream's
  cache learns a dirty flag.
- **`adventure/world/World.java`** `dispose()` - `Forge.safeDispose(biomeImage, fogOfWarPixmap, fogTilePixmap)`.
Both-sides files (16) re-checked line by line: every mod addition present. No new dependency; no Android file.

## Round 183 (2026-09-11) - one engine file: the render loop's exception key

- **`forge/Adventure.java`** `reportSilencedRenderException()` - the swallowed-exception key is now the top stack frame
  PLUS the first `forge.` frame (code review D6). Keying on the top frame alone put every call site throwing from the
  same JDK/libGDX method under one key, so only the first of them ever printed a trace. Same first-occurrence-then-count
  behavior otherwise.

Everything else this round is adventure-side (`adventure/util/SpawnTierWeighting`, `adventure/util/TerritoryControl`,
`adventure/world/WorldSave`, the roaming-guard classes, `adventure/scene/MapViewScene`, `adventure/scene/NewGameScene`,
`adventure/data/SpawnTierWeightData`, `adventure/data/TuningData`, `adventure/stage/{GameStage,MapStage,WorldStage}`)
plus `dev-tools/save-editing/Inv.java`.

## Round 184 (2026-09-11) - no engine edits

Adventure-side only: `adventure/data/BiomeData` (`getEnemy()` initializes the lazily-built enemy list itself - the
cave-load crash), `adventure/util/CardUtil` (the absolute copy cap in `generate()`, the new `"startingColor"`
predicate token and `templateMainSize()`), `adventure/util/Config` (`racedStarterDeck()`'s widening trigger),
`adventure/util/DungeonRotation` (the `NoRotate` tag), `adventure/util/SpawnTierWeighting` (the color-skew label),
and the plane's `world/items.json`, `world/points_of_interest.json`, `maps/map/main_story/spawn.tmx` and two
`sprites/enemy/basic/humanoid` atlases.

## Round 185 (2026-09-12) - no engine edits

Adventure-side only: `adventure/world/World` (new `hasUnexploredIn()`), `adventure/stage/WorldBackground` (the
discovery flash uses `flashArea` gated on it), `adventure/data/DialogData` + `adventure/util/MapDialog` (the
opt-in `greyOutIfUnavailable` disabled-button path), and plane data (`ancient_diamond_mine.tmx`, 14 maps' stray
`Collision` cells, version stamps).

## Round 189 (2026-09-12) - no engine edits

Adventure-side only: `adventure/util/TerritoryControl` (a `[TFR-TerritoryPerf]` line per in-game day
and the phase timers behind it). Diagnostic only - no behaviour change.

## Round 188 (2026-09-12) - no engine edits

Adventure-side only: `adventure/stage/WorldStage` (`clearCache()` now clears `waitingForTime` and
`fastTimeEnabled`, plus a corrected comment about which paths reach that reset) and
`adventure/stage/GameHUD` (one `syncCheckBox()` helper; the Speed-Up box is now read back from
WorldStage the way the Wait box always was).

Worth knowing: `WorldStage.clearCache()` is reached by BOTH a save load (`WorldStage.load()` calls it
first) and a new game (World's generation calls it last), so it is the right home for any singleton
state that must not outlive a save. A comment in WorldStage used to claim new games bypassed it; that
was wrong and is now fixed.

## Round 187 (2026-09-12) - ONE engine file: the startup mode selector

`forge/Forge.java` (startup, ~line 218). User: "Have the game go directly to the Adventure Main screen
(Load/Save screen), not need to ask for Forge/Adventure selection." The boot reads
`FPref.UI_SELECTOR_MODE` into `Forge.selector`, and the Forge/Adventure picker is shown only when that
value is neither "Classic" nor "Adventure" - i.e. when it is the shipped default "Default". Three lines
now map "Default" to "Adventure", INSIDE the existing `Files.exists(... ADV_TEXTURE_BG_FILE)` guard so a
build without adventure assets can never be forced into a mode it cannot draw.

Deliberately NOT done by changing `ForgePreferences.UI_SELECTOR_MODE`'s default (which is where the
value "Default" comes from, `forge-gui/src/main/java/forge/localinstance/properties/ForgePreferences.java`):
a default only reaches a profile that has never stored the key, and an existing player's preferences file
already holds "Default", where the stored value wins. Mapping at read time reaches both. An explicit
"Classic" in Settings is untouched, and the Settings entry still offers all three values.

MERGE NOTE: this is a small edit in a file upstream changes often. If a merge conflicts here, the whole
change is the `if ("Default".equals(selector)) selector = "Adventure";` line plus the braces around the
existing assignment.

Adventure-side for this round: `adventure/character/EnemySprite` (new `setEffect()` + `applyCrownSizeFloor()`
and its apply-once flag), its three callers `adventure/stage/MapStage`, `adventure/stage/WorldStage`
and `adventure/util/MapDialog`, `adventure/data/AdventureQuestData` (new `SOURCE_POI_TOKEN` and
`replaceSourcePOIToken()`), and plane data (`world/quests.json`: Wanderlust's epilogue).

No Forge-core file touched. Worth knowing for future quest data: MapDialog's `addMapReputation`
treats a POIReference it cannot resolve as "grant at the POI the player is standing in" (a
deliberate "the player gets *something*" fallback, see the comment there), so a token that fails to
resolve does not go nowhere - it silently redirects the grant to the current town. Any new POI
token has to fail safe by cancelling the grant instead, which is what `replaceSourcePOIToken()`
does.

## Round 186 (2026-09-12) - no engine edits

Adventure-side only: `adventure/stage/GameStage` (the F12 overlay is a tracked toggle - new `collisionDebug`
field + `isCollisionDebug()`, F12 flips it instead of only ever switching it on, and a duplicate F11 branch
removed), `adventure/stage/MapStage` (`loadMap()` ends with `debugCollision(false)` so the singleton stops
carrying the overlay's flags into the next map; `debugCollision` builds its Group only when switching ON),
`adventure/scene/ArenaScene` (new `fitToFighterSpot()` + `ARENA_PORTRAIT_FILL`, and height-based Y centring in
the bracket and in `markLostFighter`), and plane data (`cave_amphin.tmx`: three gids re-pointed from `main.tsx`
to `main-nocollide.tsx`).

No Forge-core file touched. Of note for future map work, and NOT changed: libGDX's own
`MapStage.loadCollision()` reads `cell.getTile().getObjects()` - the UNFLIPPED tile - so a TMX cell with flip
bits keeps its collision box on the original side. Real, but plane-wide it is 22 cells whose boxes are
near-full-tile walls inset by 1-3px, i.e. a 2px discrepancy. See `dev-tools/flipped_collision_qa.py`.
