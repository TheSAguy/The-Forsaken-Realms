# The Forsaken Realms - deep code review

Started 2026-09-05 against commit `612418069cc` (Round 122). Reviewer: Claude (solo, no subagents).
Ground rules as agreed: review only, no code changes, findings cite `path:line`, every finding is
labelled CONFIRMED (code path traced, concrete failure scenario) or SUSPECTED. Severity is player
impact: Critical = crash / save corruption / data loss, High = wrong game behavior or severe
performance loss, Medium = noticeable defect or significant maintenance risk, Low = the rest.

**Status: COMPLETE (2026-09-05, round 123). Phases 0-4 done; 11 findings fixed in this round (section 5, 'Fix now'); data validation report at `docs/review/2026-09-05-data-validation.txt`.**

---

## 1. Executive summary

The Forsaken Realms is a large mod (about 457k added lines against the upstream merge base, most of it data and
decks; roughly 30k lines of new Java) whose game logic is mostly well isolated in new classes, consistently
commented, and unusually well instrumented with `[TFR-*]` diagnostics. The review read every mod-owned source file
and every upstream file the mod edits, validated all plane data against the loader classes, and confirmed findings
by tracing code paths rather than by running the game. It found **3 Critical, 7 High, 7 Medium and 20 Low**
findings, of which 11 were fixed in this round (round 123) because they were local and unambiguous.

**Memory.** The most serious defects are native-memory leaks that the Java heap never shows: every minimap re-bake
leaked a 31 MB pixmap (S2-1), every ground tile composited for the fog of war leaked its pixmaps at a rate of about
100 KB per tile stepped (S2-2), and every map entered leaked its tileset textures (S4-1, upstream). Long sessions -
the mod's normal play pattern - grew the process by hundreds of MB. All three are fixed.

**Territory ownership.** A sacked player town kept its ownership flag and was re-expanded as player land the next
day (S2-3, fixed), and per-town state orphaned by a capture comes back, buildings and all, when the town's name
round-trips through capture and revert (S2-4, documented with a fix plan). Both stem from town identity being derived
from a name that ownership changes rewrite (4.3).

**Economy.** The Ante Re-roll charged shards that the end-of-match write-back then refunded, so the feature was free
(S3-1, fixed). Every other spend site is correctly gated.

**Data.** The plane's JSON has no unknown keys, no parse failures and no dangling atlas, map, deck or quest references,
but 33 enemy reward items do not exist (S3-2), 45 map placements name enemies that do not exist and are silently
replaced by random roamers (S4-4), and two town maps name shop types that do not exist (S3-4). A validation script is
provided and should run before each packaging round.

**Save/load.** All persisted classes are now pinned. Two behaviors remain dangerous: a load failure silently
regenerates the world and the next autosave overwrites the original (S1-1, upstream behavior widened by the mod's nine
load hooks), and a malformed `config.json` silently disables every mod feature (S2-6). Both need loud failure
before v1.06.

**Housekeeping.** No tests cover the adventure package; the tracked `.claude/settings.json` grants
`bypassPermissions` to any clone; packages do not record the commit they were built from. The upstream merge for
v1.06 will conflict first in `World.java`, `WorldStage.java`, `MapStage.java`, `AdventurePlayer.java` and
`RewardScene.java`; splitting `World.java`'s mod regions into helpers beforehand is mechanical and worth it.

## 2. Findings table

| ID | Severity | Confidence | Subsystem | Summary | Location |
|---|---|---|---|---|---|
| S2-1 | Critical | CONFIRMED | World / minimap | Every minimap re-bake leaks the previous 31 MB `biomeImage` (fixed) | `world/World.java:2288`, `:1850` |
| S2-2 | Critical | CONFIRMED | Terrain rendering | Per-tile ground pixmaps never disposed; fog repatch loop multiplies the rate (fixed) | `world/World.java:833-842`, `stage/WorldBackground.java:235-247, 396-397` |
| S1-1 | Critical | CONFIRMED | Save/load | Any exception while loading the world silently regenerates it; next autosave overwrites the save | `world/WorldSave.java:91-156, 318-319` |
| S1-2 | High | SUSPECTED | Save/load | A `RuntimeException` during `save()` leaves an empty `.sav` and no backup restore (fixed) | `world/WorldSave.java:262-321` |
| S1-3 | High | CONFIRMED (latent) | Save/load | Inventory read failure swallowed, quests/events silently cleared; `QuestFlag` unpinned (pin applied) | `player/AdventurePlayer.java:1028-1061, 1142-1158`, `data/DialogData.java:61` |
| S1-4 | High | CONFIRMED (latent) | Save/load | `SaveFileData` read by a plain `ObjectInputStream` with a derived UID (pin applied) | `util/SaveFileData.java:15`, `world/WorldSave.java:86-88` |
| S2-3 | High | CONFIRMED | Territory Control | A sacked player town keeps `TOWN_RESTORED_FLAG` and stays player-owned (fixed) | `util/TerritoryControl.java:2129-2135` |
| S2-4 | High | CONFIRMED | Territory Control | Orphaned per-town state resurrects when a town's name round-trips (capture then revert) | `pointofintrest/PointOfInterest.java:160-170`, `util/TerritoryControl.java` (`matchingTownData`/`matchingWasteData`) |
| S3-1 | High | CONFIRMED | Economy / ante | Ante Re-roll cost is refunded by the end-of-match shard write-back (fixed) | `screens/match/MatchController.java:722`, `scene/DuelScene.java:157` |
| S4-1 | High | CONFIRMED (upstream) | Scenes | Every map entered leaks its tileset textures (fixed) | `scene/TileMapScene.java:219, 243` |
| S1-5 | Medium | CONFIRMED | Quests | Quest timers keyed by template id; copies of one template share a clock | `util/QuestExpiry.java:49-55` |
| S1-6 | Medium | SUSPECTED | Save/load | Empty catch in `WorldStage.load` hides a partial load | `stage/WorldStage.java:1581-1649` |
| S1-7 | Medium | CONFIRMED | World generation | World not reproducible from its seed (`MyRandom`, unseeded futures) | `world/World.java:57, 596, 1019`, `data/BiomeData.java:50, 212, 227` |
| S2-5 | Medium | CONFIRMED | Territory Control | Static fingerprints/re-contest days/defense tally survive load and new game (fixed) | `util/TerritoryControl.java:491-492, 1587-1589` |
| S2-6 | Medium | CONFIRMED | Config | Malformed `config.json`/`settings.json` silently falls back to defaults with every feature off | `util/Config.java:127-133, 149-158` |
| S3-2 | Medium | CONFIRMED | Data / rewards | 33 reward items named by 26 roaming enemies and 7 bosses do not exist | `world/enemies.json` (validator `ref-item`) |
| S4-2 | Medium | CONFIRMED (upstream) | Scenes | `resetMapRecursive` parses maps with textures and never disposes them (fixed) | `stage/MapStage.java:1539, 1562-1564` |
| S1-8 | Low | CONFIRMED | Save/load | Parallel lists persisted separately | `pointofintrest/PointOfInterestChanges.java:91-92, 185-196, 270-293` |
| S1-9 | Low | CONFIRMED | Data classes | Dead copy constructors, one would NPE if used | `data/ItemData.java:49-65`, `data/DialogData.java:148-185`, `data/EffectData.java:41-50` |
| S2-7 | Low | SUSPECTED (upstream) | World generation | WFC failure branch indexes past the array (fixed) | `world/BiomeStructure.java:96-100` |
| S3-3 | Low | CONFIRMED | Data / rewards | Reward `type` is case-sensitive; Slobad's `"Card"` yields nothing | `world/enemies.json` [390], `data/RewardData.java:280-332` |
| S3-4 | Low | CONFIRMED | Data / shops | Town maps name shop types that do not exist; slot skipped or random | `maps/map/towns/swamp_town.tmx`, `plains_town_generic.tmx`, `stage/MapStage.java:1306-1321` |
| S3-5 | Low | CONFIRMED | Data / shops | "Random" shop's `RandomShop` region missing; player-town shop without a sign | `world/shops.json:6440-6443` |
| S3-6 | Low | CONFIRMED | Content filter | CSV write-back into the install folder; read-only install or a short row disables the filter; empty sketchbook pool crashes a shop | `util/ContentFilterTables.java:74-111, 270-283`, `data/RewardData.java:498` |
| S3-7 | Low | SUSPECTED | Quests | Side-quest fallback ignores source-tag and color-status gates | `util/AdventureQuestController.java:722-725` |
| S3-8 | Low | CONFIRMED | Dev tools | Deck Tester plays AI matches on daemon threads against shared engine state | `util/DeckTesterSimulator.java:78-238` |
| S3-9 | Low | CONFIRMED | RNG | Shop generation reseeds the shared world generator; later rolls replayable | `stage/MapStage.java:212, 577, 1352` |
| S3-10 | Low | CONFIRMED | Shops | Shared `ShopData` catalog mutated per map | `stage/MapStage.java:208-209, 1316, 1343` |
| S4-3 | Low | CONFIRMED | Duels | Assault/Capitol defenders get tile (0,0)'s day/night terrain modifier | `scene/DuelScene.java:823-826`, `stage/WorldStage.java:741, 779` |
| S4-4 | Low | CONFIRMED | Data / maps | 45 map placements name unknown enemies; random roamers substituted | `maps/map/skep/skep_outer.tmx` and 4 others, `stage/MapStage.java:959-965` |
| S4-5 | Low | CONFIRMED | Flags | Byte-typed flags wrap at 128; only `townsRestored` is capped | `player/AdventurePlayer.java:2480-2494`, `stage/MapStage.java:1994-2012` |
| S4-6 | Low | CONFIRMED (upstream) | Render loop | NPE/ISE swallowed per frame with no log | `forge-gui-mobile/src/forge/Adventure.java:76-79` |
| S4-7 | Low | CONFIRMED | HUD | Notifications shown serially, 15 s each; bursts drain for minutes | `stage/GameHUD.java:1322-1369` |
| S6-1 | Low | CONFIRMED | Repository | Tracked `.claude/settings.json` sets `bypassPermissions` | `.claude/settings.json` |
| S6-2 | Low | CONFIRMED | Tests | No tests for `forge.adventure` | - |
| S6-3 | Low | CONFIRMED | Packaging | Package does not record its source commit | `standalone-packaging/build_standalone.py` |
| S6-4 | Low | SUSPECTED | Licensing | User-supplied art not attributed if any of it is third-party | `standalone-packaging/CREDITS.md` |

## 3. Detailed findings by subsystem

### 3.1 Pass 1 - Save/load and the persisted model

Files read end to end: `WorldSave`, `WorldSaveHeader`, `SaveFileData`, `AdventurePlayer`, `PlayerStatistic`,
`PointOfInterest`, `PointOfInterestChanges`, `PointOfInterestMap`, `SpritesDataMap`, `BiomeSpriteData`,
`AdventureQuestController`, `AdventureQuestData`, `AdventureQuestStage`, `AdventureEventController`,
`AdventureEventData`, `DialogData`, `ItemData`, `EffectData`, `EnemyData`, `RewardData`, `WorldData`, `BiomeData`,
`QuestExpiry`, plus `World.load/save` and `WorldStage.load/save`.

**How a save is actually shaped (verified).** `WorldSave.save()` writes, through a plain `ObjectOutputStream`
over a `DeflaterOutputStream`, first the `WorldSaveHeader` and then one `SaveFileData`
(`HashMap<String, byte[]>`). Each value is its own Java-serialized stream. Reading goes through
`SaveFileData.DecompressibleInputStream` (`util/SaveFileData.java:327-360`), which, on a `serialVersionUID`
mismatch, substitutes the local class descriptor for the stream's. That substitution is only correct when the
field layout is identical (a method-only change); when a field was added or removed the reader consumes the
wrong bytes and the value's `readObject` fails, which every caller in this subsystem turns into a default
(`readObject` returns `null`, `util/SaveFileData.java:179-193`). So the failure mode of a serialization drift is
never an error dialog: it is a silently defaulted field, an empty list, or (see S1-3) an empty inventory.

Effective serialization surface, measured on the built jar with `ObjectStreamClass.lookup` (program in the
review scratchpad, output kept as `docs/review/serial-survey` data below): 29 Serializable classes under
`forge.adventure`, 23 with a declared `serialVersionUID`. Derived (unpinned) and reachable from a save:
`util/SaveFileData` (`2370928267361276519`), `data/DialogData$ActionData$QuestFlag` (`-3808510202986416615`),
`data/AdventureEventData$AdventureEventHuman` (`-6819102903640880265`). Derived but not Java-serialized into
saves (they persist through `SaveFileContent` key/value instead): `pointofintrest/PointOfInterestChanges$Map`,
`world/SpritesDataMap$BiomeSpriteDataMap`. Core classes that ride along inside saves (`forge.deck.Deck`,
`CardPool`, `forge.item.PaperCard`, `forge.util.ItemPool`) are all pinned upstream. The v1.05 release jar and
the current build have byte-identical UID tables, so rounds 120-122 did not change the save format.

#### S1-1  Any exception during world load silently replaces the player's world with a new random one - Critical, CONFIRMED

`world/WorldSave.java:91-156` (upstream shape, mod content):

```java
try {
    currentSave.world.load(mainData.readSubData("world"));
    currentSave.pointOfInterestChanges.load(mainData.readSubData("pointOfInterestChanges"));
    currentSave.world.rebuildPlayerTownVision();                                  // mod
    forge.adventure.util.TownRestoration.repairCapitolState(currentSave.world);   // mod
    forge.adventure.util.TownRestoration.repairAllTownVisionReveal(currentSave.world); // mod
    ... updateTownLifeBonus, updateRingLifeBonus, reservePlayerEditions, rebuildFogOfWarPixmap,
    WorldStage.getInstance().load(...), prewarmTerritoryControlCaches(), chunk reload loop      // mod
} catch (Exception e) {
    System.err.println("Generating New World");
    if (!currentSave.world.generateNew(0))
        return false;
}
currentSave.onLoadList.emit();
```

The catch-all is upstream, but the mod put nine more calls and two loops inside it, and `World.load()` itself
now ends with two more mod repair passes (`world/World.java:753-756`). Whatever throws, the recovery is: keep
the already-loaded player (inventory, decks, position), generate a brand-new world from a random seed, report
success. The player stands at their old coordinates in a world that never had their towns, Capitol, guards,
territory, dungeon rotation state or fog map. The only trace is one stderr line. The next autosave - there
are eight autosave call sites, e.g. `stage/WorldStage.java:470,630,772,793,815,1016,1073` - writes the new
world over the slot and `save()` deletes the `.old` backup on success (`world/WorldSave.java:318-319`).

Concrete trigger that exists today: `pointofintrest/PointOfInterest.java:24-49`

```java
data=PointOfInterestData.getPointOfInterest(saveFileData.readString("name"));   // null for an unknown name
...
else { active = data.active; }                                                  // NPE
```

`PointOfInterestData.getPointOfInterest()` returns `null` when `points_of_interest.json` no longer has an
entry with the saved name (`data/PointOfInterestData.java:49-54`). Renaming or deleting any of the 335 POI
entries therefore turns every save that placed that entry into the scenario above. The name set is identical
between the v1.05 release and HEAD (verified by diffing both files), so no shipped build has triggered it yet,
but the plane data is edited in most rounds and nothing guards it.

Why it matters: this is the one place a data edit, or any bug in ~2,000 lines of mod post-load code, converts
into irreversible loss of a multi-week playthrough with no message.

Fix: (1) never regenerate on a load failure - log the stack, return `false`, and let `SaveLoadScene` show
"could not load slot N"; (2) move the mod's post-load repair calls out of the catch-all, or wrap each in its
own guard that logs and continues; (3) make `PointOfInterest.load()` tolerate an unknown data name (log and
mark inactive, or resolve through a rename alias table) so data renames degrade gracefully; (4) add the rule
"POI `name` fields are save keys and must never change" to `CLAUDE.md`, and a startup check that every name
in a loaded save still exists. Blast radius: `WorldSave`, `PointOfInterest`, the load scene. Upstream-merge
friction: neutral if done as a wrapper around the existing block.

#### S1-2  Save writes rename the live save before serializing and only restore it on IOException - High, SUSPECTED

`world/WorldSave.java:262-321`:

```java
if (currentFile.exists())
    currentFile.renameTo(backupFile);            // .sav -> .old, BEFORE anything is serialized
try {
    try (FileOutputStream fos = new FileOutputStream(fileName); ...) {   // creates an empty .sav
        SaveFileData player = currentSave.player.save();
        SaveFileData world = currentSave.world.save();
        SaveFileData worldStage = WorldStage.getInstance().save();
        SaveFileData poiChanges = currentSave.pointOfInterestChanges.save();
        ...
} catch (IOException e) {
    restoreBackup(oldFileName, fileName);
```

Only `IOException` restores the backup. A `RuntimeException` from any of the four `save()` methods (the mod
added roughly 700 lines to them) propagates out, leaving a zero-byte `.sav` beside a good `.old`. On the next
load the empty file exists, so `WorldSave.load()` tries it, the `ObjectInputStream` constructor throws
`EOFException`, and the slot shows as unloadable; nothing offers the `.old`. I could not construct a
RuntimeException trigger from the current code (the `adventureMode.toString()` at
`player/AdventurePlayer.java:1419` is the closest, and `adventureMode` is always set after `create`/`load`),
hence SUSPECTED, but the structure guarantees that the first such bug costs the slot.

Fix: catch `Exception`, not `IOException`, around the whole write and restore the backup; better, write to a
temp file and rename over the `.sav` only after a successful flush. Also keep the `.old` instead of deleting
it at `:318-319` - it is the only backup the game ever makes. Upstream file; ~10 lines; low merge friction.

#### S1-3  Serialization drift silently empties inventories and deletes every quest - High, CONFIRMED (latent)

`player/AdventurePlayer.java:1028-1061` wraps `(ItemData[]) data.readObject("inventory")` in
`catch (Exception ignored)` and falls back to a legacy `String[]` read that also fails, so the inventory is
simply empty; `:1142-1158` reads `quests` and `events` as `(Object[]) data.readObject(...)` and treats a
`null` result as "no quests" with no message at all. Both paths are reached whenever any class in those object
graphs has a derived `serialVersionUID` that changed shape (see the mechanism at the top of this section).

The graphs contain exactly two such classes today: `DialogData$ActionData$QuestFlag`
(`data/DialogData.java:61-64`, no UID, reachable from every quest and, via `dialogOnUse`, from the inventory of
anyone carrying "Sir Donovan's Amulet" - `world/items.json` is the only item whose dialog sets a flag) and
`AdventureEventData$AdventureEventHuman` (`data/AdventureEventData.java:1158`, reachable from `events` and
from `PlayerStatistic.completedEvents`). This has already happened once in this fork's history: round 88's
`EffectData` change wiped inventories, and round 90 pinned ten classes in response. The two left unpinned are
the remaining ways to repeat it, and the quest path is worse than the inventory path because the story chain
is lost without any dialog.

Fix (applied in this review, see section 5): pin both at their current derived values
(`QuestFlag = -3808510202986416615L`, `AdventureEventHuman = -6819102903640880265L`), which changes nothing for
existing saves. Longer term: log loudly and show the migration dialog for the quest/event path too, and add
the survey program as a CI check that fails when a class under `forge.adventure` implements `Serializable`
without a declared UID.

#### S1-4  The top-level save container has no pinned UID and is read by a non-tolerant stream - High, CONFIRMED (latent)

`util/SaveFileData.java:15` declares `public class SaveFileData extends HashMap<String, byte[]>` with no
`serialVersionUID`; its derived value is `2370928267361276519`. `world/WorldSave.java:86-88` reads it with a
plain `new ObjectInputStream(inf)` - not the tolerant subclass - so a mismatch is a hard
`InvalidClassException`, caught at `:161-164` as `IOException` and turned into "load failed" for every save
on disk. The derived UID of a class with no fields changes whenever a public method is added or removed;
`SaveFileData` is an upstream file the mod merges several times a week. The identical value in the v1.05 jar
shows it has not moved recently, but one upstream `store(String, X)` overload would invalidate every player's
saves at the next engine merge, and the standing merge procedure has no step that would notice.

Fix (applied in this review): pin `serialVersionUID = 2370928267361276519L`. Add "run the serialization survey
and diff it against the previous release" to the engine-merge checklist in `CLAUDE.md`.

#### S1-5  Side-quest timers are keyed by template id, so two copies of the same quest share one clock - Medium, CONFIRMED

`util/QuestExpiry.java:49-55`:

```java
String key = String.valueOf(quest.getID());
Integer accepted = world.getQuestAcceptedDay().get(key);
```

`AdventureQuestData.getID()` returns the template's `id` for every instantiated copy
(`data/AdventureQuestData.java:39-44,109`), and nothing in `AdventureQuestController.getQuestNPCResponse()`
(`util/AdventureQuestController.java:699-728`) prevents two towns from offering the same template while the
first copy is still active. Scenario: accept template 12 in town A on day 3 (stamped by the day tick on day 4),
accept template 12 again in town B on day 15. The second copy finds the day-4 stamp and fails on day 24
instead of day 35; when the first copy fails, `world.getQuestAcceptedDay().remove(key)` also drops the second
copy's clock, which then restarts from scratch. Fix: key the map by `stageID`-style per-instance identity (give
`AdventureQuestData` an instance UUID assigned in the copy constructor, persisted; it is Serializable and
UID-pinned, so the field is additive).

#### S1-6  Empty catch in roaming-enemy restore drops the rest of the list and the global timer - Medium, SUSPECTED

`stage/WorldStage.java:1581-1649`: the whole restore loop, including the mod's territory-mage re-linking, sits in
`catch (Exception e) { }`. Any exception at index i drops enemies i..n and skips `globalTimer =
data.readFloat("globalTimer")`, so the timer restarts at 0 and every restored enemy's lifetime math is off.
The five parallel lists (`timeouts/names/x/y/questStageIDs`) are read without a length check, so a save in
which one blob failed to deserialize (returns `null`) throws `NullPointerException` on the first index. I found
no current writer that produces such a save, hence SUSPECTED; the fix is a log line and a per-entry guard.

#### S1-7  World generation is not reproducible from its seed, and the world RNG is never re-seeded on load - Medium, CONFIRMED

`world/World.java:57` creates `random = new Random()`; `generateNew()` seeds it (`:1019`) but `load()` only
reads the stored seed (`:596`) and never calls `random.setSeed`, so everything that draws from
`world.getRandom()` after a load (shop seeds `pointofintrest/PointOfInterestChanges.java:339`, reward rolls
`data/RewardData.java:267`, territory decisions) uses a wall-clock-seeded stream. During generation itself,
town names come from `Aggregates.removeRandom` on Forge's global `MyRandom` (`data/BiomeData.java:227`), enemy
picks use `MyRandom` (`data/BiomeData.java:50,212`), and three `CompletableFuture.supplyAsync` fan-outs
(`world/World.java:1094-1126,1670-1748`) run structure generation on the common pool. Two games started with
the same seed therefore get different town names and can get different structures. Impact today is low (no
UI advertises seeds - to be re-checked in the scene pass), but it means a "reproduce this world" bug report
is impossible. Fix: a `Random` per generation task derived from `seed + taskIndex`, `MyRandom`-free naming,
and `random.setSeed(seed ^ dayCount)` on load if runtime determinism is ever wanted.

#### S1-8  Save-time shape of several inner collections is order-dependent and unguarded - Low, CONFIRMED

`pointofintrest/PointOfInterestChanges.java:91-92,185-196,270-293`: `guardTiers` and `guardLastPaidDay` are
parallel lists restored independently; a partial read leaves them different lengths and
`getGuardLastPaidDay(i)` throws in the weekly salary sweep. Same pattern for `equippedSlots/equippedItems`
(`player/AdventurePlayer.java:1062-1084`, at least guarded by an `assert` that is off at runtime) and for
`WorldStage`'s enemy lists. A single list of small records, or a length check with a log, removes the class of
bug.

#### S1-9  Dead copy constructors that would be wrong if anyone used them - Low, CONFIRMED

`data/ItemData.java:49-65` (`new EffectData(cpy.effect)` NPEs for the many items with no `effect` block; no
callers - `ItemListData.getItem` clones), `data/DialogData.java:148-185` (`ActionData` copy drops
`advanceCharacterFlag`, `setCharacterFlag` and the mod's `grantRingGift`; no callers), `data/EffectData.java:41-50`
(drops `moveSpeed`, `goldModifier`, `cardRewardBonus`, `visionRadiusMultiplier`, `startBattleWithCardInCommandZone`;
only reachable through the unused `ItemData` copy). Delete them or complete them; today they are traps for the
next contributor.

#### Observations carried to later passes

- `AdventurePlayer.takeShards()` (`:2045-2050`) has no zero floor while `takeGold()` (`:2018-2039`) was given one
  after a real negative-gold report. The two Pass-1 callers (inventory use, HUD ability) are gated; every shop,
  research and building spend is checked in Pass 3.
- `AdventureQuestStage.handleEvent()` `case Use` (`data/AdventureQuestStage.java:474-478`) has an operator-precedence
  bug that dereferences `event.item` for every non-item event once `itemNames` is non-empty; no stage in this
  plane's `quests.json` uses the `Use` objective, so it is unreachable here. Upstream.
- `AdventureQuestController.getBoostedSpawns()` (`:149-151`) dereferences `getTargetEnemyData()` for non-mixed
  Defeat stages; all 20 of the mod's color-filter Defeat stages are `mixedEnemies: true` (verified), so the
  `null` case is unreachable with current data.
- The quest controller is `Serializable` with a UID pin but is never serialized (no `storeObject` of it
  anywhere); `mostRecentPOI` is therefore not restored on load. Harmless, misleading.
- Inn events are keyed to the real-world date (`LocalDate.now()` in `util/AdventureEventController.java:117,174,202`
  and `util/AdventureQuestController.java:669,692`) while every mod system runs on the in-game day counter.
  Cross-cutting, see section 4.
- `PointOfInterest.transformInto()` changes `getID()`, so every per-POI map keyed by id (`pointOfInterestChanges`,
  `poiDespawnDay`, `poiFailedAttempts`, `questAcceptedDay` targets) keeps a stale entry after a capture. The
  comment says this is intended; the save grows by one orphaned entry per capture. Cross-cutting.


### 3.2 Pass 2 - World generation, terrain rendering and Territory Control

Files read end to end: `world/World.java` (4356 lines), `stage/WorldBackground.java`, `stage/WorldStage.java`,
`world/BiomeStructure.java`, `world/BiomeSprites.java`, `world/ColorMap.java`, `world/BiomeTexture.java` (1-220;
220-409 is untouched upstream), `util/TerritoryControl.java`, `util/DungeonRotation.java`, `util/SpawnTierWeighting.java`,
`util/ColorReputation.java`, `util/ResourceSpawns.java`, `util/Config.java` (constructor and loaders), `data/TuningData.java`,
`data/ConfigData.java`, `scene/WorldStandingsScene.java` (resource lifecycle only). libGDX's `Pixmap` was checked in the
1.14.2 jar with `javap`: it has no finalizer or cleaner, so native pixel memory is released only by `dispose()`.

#### S2-1 (Critical, CONFIRMED) - every minimap re-bake leaks the previous 31 MB `biomeImage`

`world/World.java:2239-2294`:

```java
private void rebakeMinimapAfterTerritoryControl() {
    Pixmap pix = new Pixmap(width * data.miniMapTileSize, height * data.miniMapTileSize, Pixmap.Format.RGBA8888);
    ...
    pixmapHash.clear();
    biomeImage = pix;          // :2288 - the previous biomeImage is never disposed
```

With `world.json`'s width 700, height 700, miniMapTileSize 4 the pixmap is 2800 x 2800 x 4 bytes = 31,360,000 bytes of
native memory per bake. The old one is simply overwritten. `load()` disposes the previous image (`:573`), `dispose()`
disposes it (`:4339`), but the two runtime writers do not: `:2288` here and `generateNew` at `:1850`
(`biomeImage = pix;` with no dispose of the image left from the previous game in the same session).

Callers of the rebake, all through `refreshWorldMapMarkers()` (`:2227-2237`): `DungeonRotation.java:170,240,333,361`
(every rotation batch, i.e. every 3 in-game days), `TerritoryControl.java:1078` (`updateAiTownGuardLevels`, daily),
`TownRestoration.java:402,992` (each player capture / capitol upgrade), `World.addPointOfInterestNear` `:2218`
(capital repair). A session that plays 30 in-game days therefore leaks on the order of 10 x 31 MB = 300 MB of native
memory from this site alone. On desktop this shows up as slowly growing RSS and eventually an out-of-memory abort in
native code; on Android (the packaged APK path in `ANDROID_RELEASE.md`) the process is killed much sooner.

Failure scenario: start a game with Territory Control on, hold Speed-Up for a few in-game weeks, watch the process
memory climb by ~31 MB every 3 days; nothing in the Java heap grows, so a heap dump shows nothing.

Fix (applied in this round, see section 5): dispose the old image before assignment in both writers:

```java
Pixmap old = biomeImage;
biomeImage = pix;
if (old != null && old != pix) old.dispose();
```

The `GameHUD.refreshMiniMap()` (`:571-585`) and `MapViewScene.refreshMap()` (`:452-459`) texture sites dispose their
old textures correctly and never take ownership of the pixmap, so they are unaffected.

#### S2-2 (Critical, CONFIRMED) - per-tile ground sprites are never disposed; the mod's fog repatch loop multiplies the rate

`world/World.java:833-842`:

```java
public Pixmap getBiomeSprite(int x, int y) {
    if (x < 0 || y <= 0 || x >= width || y > height)
        return new Pixmap(data.tileSize, data.tileSize, Pixmap.Format.RGBA8888);
    if (!isExploredWorld(x, y))
        return getFogTile();                 // shared, must NOT be disposed
    Pixmap real = generateBiomeSprite(x, y); // fresh, except the map-edge case below
    if (isFogOfWarEnabled() && !isCurrentlyVisible(x, y))
        return hazeTile(real);               // fresh copy; `real` is abandoned
    return real;
}
```

`generateBiomeSprite` (`:847-`) allocates `drawingPixmap` at `:850` and, for a map-edge tile, returns the shared
`regions.getPixmap(biomeTerrain)` at `:858` while abandoning the `drawingPixmap` it already allocated. `hazeTile`
(`:4216-4227`) copies `real` into a new pixmap and drops `real`.

The two consumers never dispose what they get back:

```java
// stage/WorldBackground.java:235-247 (upstream)
newChunk.draw(WorldSave.getCurrentSave().getWorld().getBiomeSprite(cx + chunkSize * x, cy + chunkSize * y), ...);
// stage/WorldBackground.java:396-397 (mod)
Pixmap tile = WorldSave.getCurrentSave().getWorld().getBiomeSprite(worldTileX, worldTileY);
tex.draw(tile, localX * tileSize, (chunkSize * tileSize) - (localY + 1) * tileSize);
```

The chunk build is upstream's leak (chunkSize^2 tiles per chunk, e.g. 80 x 80 = 6,400 tiles = 6.5 MB per chunk at
1280 px wide; chunks are evicted and rebuilt by `onTileRevealed` `:388-391` whenever territory repaints touch them,
so this is not one-time). What is new in the mod is the repatch loop in `WorldBackground.draw()` `:99-108`: every
time the player's tile position changes, `(2 * (visionRadius + 1) + 1)^2` = 81 tiles (visionRadius 3) go through
`onTileRevealed`, i.e. 81 fresh pixmaps of 1 KB each, plus a second 1 KB haze copy for every known-but-not-visible
tile, per tile stepped. Walking 1,000 tiles leaks 80-160 MB. `revealArea` and `tickTemporaryReveals` (`:89,94`) add
more per newly revealed tile. Because the pixmaps are small, the leak is invisible in a Java heap dump and shows up
only as process RSS growth and eventual native OOM, and it scales with distance walked, which is exactly the play
pattern the map-heavy mod encourages.

Failure scenario: fog of war on (the shipped default), hold a direction key for a few minutes, observe RSS growing
by tens of MB per minute with a flat Java heap.

Fix (applied): make `getBiomeSprite` always return a pixmap the caller owns (copy the shared fog tile; in the
edge case draw the shared region into the already-allocated `drawingPixmap` and return that; dispose `real` inside
`hazeTile` after copying), and dispose the returned pixmap after `draw()` at both consumer sites. The chunk build
gains one `dispose()` per tile, which is negligible against the GPU upload it already does.

#### S2-3 (High, CONFIRMED) - a sacked player town stays player-owned

`util/TerritoryControl.java:2129-2135` (inside `onMageArrived`):

```java
if (sackedInstead) {
    // ... the internal data.name ... stays "Waste Town X" the whole time, so this is already the
    // correct ruin template, no lookup needed.
    newData = PointOfInterestData.getPointOfInterest(target.getData().name);
    repaintColor = "colorless";
    isSacked = true;
}
```

Player ownership is `TownRestoration.TOWN_RESTORED_FLAG` in the town's `PointOfInterestChanges` map flags
(`TownRestoration.java:334`, `isTownRestored`). The changes map is keyed by `PointOfInterest.getID()`, which is
`seedOffset + data.name + "/" + data.map`. Transforming a player town into the template with the same name keeps the
same id, so the same `PointOfInterestChanges` object, so the flag. The flag is written at `TownRestoration.java:377,899`
and via `setFlagAction` at `:583`; a search of the whole tree finds no code path that ever removes it.

Consequences after the "was sacked ... left in ruins!" notification: `isTownRestored` still returns true, so the town
still counts as Player in `getTownCounts` and the standings, `processTerritoryExpansion` re-expands it as "player" the
next day (undoing the colorless repaint tile by tile), the Rally rune still treats it as a player town, and the life
bonus removed at `:2229` is added back by the next `updateTownLifeBonus` recount. Only the display and the AI's
mental model change.

Fix (applied): in the `wasPlayerOwned` branch of `onMageArrived`, remove `TOWN_RESTORED_FLAG` from the changes
under the *old* id before the transform (and, for a same-name sack, that is the surviving object).

#### S2-4 (High, CONFIRMED) - orphaned per-town state comes back when a town's name round-trips

`PointOfInterest.transformInto()` (`pointofintrest/PointOfInterest.java:160-170`) swaps `data` and thereby changes
`getID()`, but the `pointOfInterestChanges` entry under the old id is left in place ("re-keys" in the code comments
means "abandons"). `matchingTownData` (`TerritoryControl.java`) maps `"Waste Town 5"` to `"<Noun> Town 5"` and
`matchingWasteData` maps it straight back to `"Waste Town 5"`. So:

1. the player restores "Waste Town 5" (flag, buildings, guards, reputation, visits all stored under id K1);
2. a Blue mage captures it: the POI becomes "Blue Town 5" (id K2), K1 is orphaned but still in the save;
3. any later mage that loses the capture roll against Blue reverts it with `matchingWasteData` to "Waste Town 5"
   (id K1) - and every piece of K1's state is live again: the town is player-owned with all its buildings, at
   the reputation it had, without the player doing anything.

The same round trip resurrects `aiGuardLevel` records for AI towns (`TerritoryControl.java:1028,1050` explicitly
rely on the orphaning to "restart from a fresh entry", which is only true until the name comes back), and
`poiDespawnDay` / `poiFailedAttempts` / territory radii keyed on ids (`World.java`). This is the mechanism behind
Pass 1's "save grows by one orphaned entry per capture" note, and it is worse than growth: it is a correctness
bug. Fix: on every `transformInto` that changes the id, either move the changes entry to the new id (correct for
guards/buildings that survive a capture) or delete the old entry (correct for ownership flags). The minimal safe
change is to delete the old entry's `TOWN_RESTORED_FLAG` and the ownership-only flags when a town leaves player
hands, which S2-3's fix does, and to log a `[TFR-Orphan]` line whenever `getPointOfInterestChanges` returns an
entry whose flag set is inconsistent with the POI's current data. The full re-keying is a "fix before release"
item; see section 5.

#### S2-5 (Medium, CONFIRMED) - Territory Control keeps static per-session state across load / new game

`util/TerritoryControl.java:491-492`:

```java
private static final Map<String, Long> lastPullSourcesFingerprint = new HashMap<>();
private static final Map<String, Integer> lastFullRecontestDay = new HashMap<>();
```

plus `neutralDefenseAttempts/Repels/ExpectedRepels` at `:1587-1589`. `WorldStage.clearCache()` (called by `load()`)
resets `DungeonRotation`, `ResourceSpawns` and the pending Capitol defense only. After loading a different save (or an
older autosave of the same world) `sourcesChangedFor()` compares the new world's pull sources against the previous
world's fingerprint and `lastFullRecontestDay` holds a day number from the other timeline, so the first day tick after
a load either skips a re-contest it should run or runs one it should not. The effect is one day of wrong border
contest, self-correcting after `FORCE_RECONTEST_INTERVAL_DAYS`; the `[TFR-CaptureOdds]` "session repels" ratio mixes
games. Fix: clear the three maps/counters from `clearCache()` (and from `generateNew`).

#### S2-6 (Medium, CONFIRMED) - a malformed `config.json` or `settings.json` silently turns every mod feature off

`util/Config.java:127-133` and `:149-158`:

```java
try {
    configData = new Json().fromJson(ConfigData.class, file);
} catch (Exception e) {
    e.printStackTrace();
    configData = new ConfigData();
}
```

libGDX's `Json` throws on any unknown field name (checked in the 1.14.2 binary in Phase 0), and `ConfigData`'s
defaults for every feature flag are `false` (`data/ConfigData.java`), with `difficulties`, `colorIds`,
`starterEditions` and the rest `null`. A single typo in a key while tuning (the standing workflow in
`CLAUDE.md` edits both files often) therefore starts the game with Territory Control, fog of war, dungeon
rotation, reputation, resource spawns and the rest disabled and no on-screen message - the only trace is a stack
trace in `forge.log`. For `settings.json` (`TuningData`) the fallback is the hardcoded defaults, so a balance edit
is silently ignored. Fix: fail loudly - a startup dialog (or at least a `[TFR-Config] FAILED` banner line and a
refusal to start a new game) when the plane's own config file exists but does not parse.

#### S2-7 (Low, SUSPECTED, upstream) - `BiomeStructure` failure branch indexes past the array

`world/BiomeStructure.java:96-100`:

```java
if (!suc) {
    for (int x = 0; x < dataMap.length; x++)
        for (int y = 0; y < dataMap[x].length; y++)
            dataMap[mx + x][my + y] = -1;
    return;
}
```

The success-less branch loops over the whole `dataMap` but writes at `mx + x`, so for any chunk with `mx > 0` it
throws `ArrayIndexOutOfBoundsException` (the branch two lines above at `:86-88` loops over `chunkWidth`, which is
what was meant). It fires only when the wave-function-collapse model fails ten times for one chunk, and the exception
surfaces as a failed world generation. Upstream code; listed because the mod's 700 x 700 world with more structure
data makes the failure branch more likely to be reached than on Shandalar. Fix: loop to `chunkWidth`/`chunkHeight`.

#### Notes (not findings)

- `generateNew` is not reproducible from the seed (S1-7) and the road-carving futures write to shared arrays; the
  writes are idempotent so no corruption, only nondeterminism.
- `updateAiTownGuardLevels` (`TerritoryControl.java:~1040-1080`) calls the get-or-create
  `getPointOfInterestChanges()` for every AI town every day, so every AI town gets a changes entry on day 1 whether
  or not anything happens to it - harmless, slightly larger saves.
- `ResourceSpawns.spawnInArea` re-fetches `world.getAllPointOfInterest()` (a fresh 2,000-entry list) inside its
  200-attempt loop for each of up to 30 spawns on a refill day; measured in the `[TFR-DayTick]` line as part of
  "economy"/"territory" it is milliseconds, not a stutter source.
- The chest "Duplicate" dialog (`WorldStage.java:1104-1128`) attaches handlers to buttons it then disables; this is
  safe because `Controls.newTextButton` (`util/Controls.java:459-473`) checks `isDisabled()` at the root since
  2026-08-25. The comment on the Capitol toll dialog (`:1000-1008`) still describes the pre-fix behavior.
- `WorldStage.onActing` iterates `enemies` with an explicit iterator and calls `TerritoryControl.onMageArrived`
  inside the loop; `onMageArrived` never adds to `enemies` (it only transforms POIs, repaints and may show a
  dialog), so there is no `ConcurrentModificationException` today. Worth a comment, since any future "spawn a
  defender on arrival" feature would break it.
- `World.random` is used from the world-gen `CompletableFuture`s and from the render thread's day tick through
  `world.getRandom()` - never concurrently in practice because generation completes before the stage runs.


### 3.3 Pass 3 - Economy, towns, rewards, shops and decks

Files read end to end: `util/EconomyBuildings.java` (2880 lines), `util/TownRestoration.java`, `util/EditionProgression.java`,
`util/ChestEvents.java`, `util/ArmoryRarity.java`, `util/ContentFilterTables.java`, `util/QuestExpiry.java`,
`util/DeckTesterSimulator.java`, `util/Reward.java`, `data/RewardData.java`, `data/ItemData.java`, `data/ItemListData.java`,
`util/MapDialog.java` (action application), the mod regions of `util/CardUtil.java` and `util/AdventureQuestController.java`,
every `takeShards`/`takeWood`/`takeStone` call site in the tree (22 sites), `scene/RewardScene.java` (restock, blueprint and
Armory re-roll paths), `scene/ShardTraderScene.java`, `scene/ResearchScene.java`, `scene/InnScene.java`,
`scene/SpellSmithScene.java`, `scene/ArenaScene.java` (fee gating), and the match-side ante re-roll
(`forge-gui-mobile/src/forge/screens/match/MatchController.java`, `forge-gui/.../FControlGameEventHandler.java`,
`forge-game/.../Game.java`).

Spend-site audit result: every shard/wood/stone spend is gated. `Controls.newTextButton` (`util/Controls.java:459-473`)
and `UIActor.onButtonPress` (`util/UIActor.java:337-351`) both check `isDisabled()` at the root, every dialog option built
from `DialogData` is `isDisabled` when `canAffordCost()` fails and disabled options get no handler
(`util/MapDialog.java:273-286`), `takeGold` clamps at zero (`player/AdventurePlayer.java:2018-2039`), and the four
unfloored `takeShards/Wood/Stone` methods are only reached behind an explicit affordability check. No negative-resource
path was found in the mod's own code.

#### S3-1 (High, CONFIRMED) - the Ante Re-roll cost is refunded when the match ends

`forge-gui-mobile/src/forge/screens/match/MatchController.java:678-725` (`revealAnteCards`, runs on the game thread
while `Match.startGame` is choosing the ante):

```java
player.takeShards(cost);          // :722 - AdventurePlayer purse only
currentItems = reroll.get();
```

The in-game player's mana shards were copied from the purse when the duel was set up
(`scene/DuelScene.java:681-682`, `humanPlayer.setManaShards(advPlayer.getShards())`, applied by
`forge-game/.../Game.java:356-357`) and are copied *back* over the purse when the match ends:

```java
// scene/DuelScene.java:157
Current.player().setShards(humans.get(0).getPlayer().getNumManaShards());
```

The re-roll deducts only the purse, so the write-back restores the pre-re-roll amount (minus whatever was spent in
the game). Every re-roll of an ordinary duel is free; the HUD shows the deduction for the duration of the match and
then the shards come back. Events with `allowsShards == false` are the only path that keeps the charge. The
`[TFR-AnteReroll]` log lines look correct because they print the purse, not the game count.

Fix (applied): charge the in-game player as well - `DuelScene.chargeInGameManaShards(cost)` finds the human
`forge.game.player.Player` in `hostedMatch.getGame()` (created synchronously in `HostedMatch.startGame()` `:162`,
before the ante is chosen) and lowers `getNumManaShards()` by the same amount, so the end-of-match write-back is
consistent whether the player wins, loses or concedes.

#### S3-2 (Medium, CONFIRMED) - 26 roaming enemies reward items that do not exist

`world/enemies.json` rewards name 33 distinct items that are not in `world/items.json` (full list in
`docs/review/2026-09-05-data-validation.txt`, category `ref-item`): "Commander's Robes" (Jodah), "Opal Cloak" (The
Ur-Dragon), "Heirloom Blade" (The First Sliver), "Kill Trophy" (the 26 Ikoria commanders), the five "Key to the
Ur-Dragon" items, "Bowl of Ancient Blood" (Sengir), the Shandalar-1997 boss trophies and "Key to the False God" items,
and one literal placeholder `"Name of Item"` (Falco Spara). 26 of the enemies are in this plane's biome rosters
(`world/biomes/*.json`), i.e. ordinary roaming spawns; the rest are the Shandalar-1997 bosses and the False God set.
`data/RewardData.java:438-442`:

```java
ItemData itemData = ItemListData.getItem(itemName);
if (itemData != null)
    ret.add(new Reward(itemData));
else
    System.err.println("Missing item: " + itemName);
```

The player beats a Mythic-tier Ur-Dragon and gets its gold and cards but not the item the data promises; the
only trace is a stderr line. Most of these are items the mod's curated `items.json` dropped. Fix: either add the
items back or delete the reward entries; the validation script re-run gives the list.

#### S3-3 (Low, CONFIRMED) - reward `type` is case-sensitive; one boss reward is `"Card"`

`world/enemies.json` entry 390 (Slobad) declares `"type": "Card"`. `data/RewardData.java:280-332` switches on the
exact string (`case "card": case "randomCard":`) with no default branch, so the entry generates nothing, silently.
Upstream data-format weakness; the mod's own catalog carries the typo.

#### S3-4 (Low, CONFIRMED) - shop lists naming non-existent shop types fall back to the whole catalog

`maps/map/towns/swamp_town.tmx` objects 41-46 list `shopList "Horror"` and `plains_town_generic.tmx` object 51
lists `commonShopList "Everything"`; neither name is in `world/shops.json`. `stage/MapStage.java:1306-1311`:

```java
if (filteredPossibleShops.isEmpty()) {
    filteredPossibleShops = possibleShops;
}
Array<ShopData> shops;
if (filteredPossibleShops.size == 0 || shopList.isEmpty())
    shops = WorldData.getShopList();
```

`filteredPossibleShops` is non-empty (it holds the unknown name), so the inner loop over `WorldData.getShopList()`
matches nothing and `shops.size == 0` skips the slot (`:1321`). Depending on which branch the data takes, the slot is
either silently empty or, when the list is blank, a random shop from all 308 types. Both are wrong for a map that
asked for a Horror shop. Fix the two maps (or add the shop types); the validator flags them.

#### S3-5 (Low, CONFIRMED) - the "Random" pack shop has no sign sprite

`world/shops.json:6440-6443` defines shop "Random" with `sprite "RandomShop"` in `maps/tileset/buildings.atlas`; that
region does not exist (779 atlases parsed, only this reference is dangling). "Random" is in the `commonShopList` of
`player_town.tmx`/`player_capital.tmx` (`:37,46,55,64,73`), so a player town that rolls it gets the shop with no sign
(`MapStage.java:1399-1420` catches the exception and prints "Can not create Texture"). Cosmetic, but a visible
regression for the player's own towns.

#### S3-6 (Low, CONFIRMED) - the content-filter tables are rewritten into the install folder on every start

`util/ContentFilterTables.java:270-283` (`writeCsv`) writes `config tables/expansions.csv`, `items.csv` and
`enemies.csv` under the plane folder (`Config.getFilePath`) during startup, and `loadOrRegenerateExpansions`
(`:90-111`) only computes the exclusion set *after* the write. On a read-only install (Program Files, a mounted
image, a non-writable Android asset dir) the write throws, the `catch` at `:74-77` swallows it, and every exclusion
silently stops applying - the game's content changes with file permissions. Related fragility in the same file: a
hand-edited row with fewer than 6/7/8 fields throws `ArrayIndexOutOfBoundsException` at `:98`, `:149`, `:207` and
disables the whole table the same way; and setting all 65 "Landscape Sketchbook" rows to N makes
`ItemListData.getSketchBooks()` empty, after which any `landSketchbookShop` reward (`shops.json` uses it) throws
`IllegalArgumentException` from `rewardRandom.nextInt(0)` at `data/RewardData.java:498`. Fix: compute exclusions
from the merged rows before writing, treat a failed write as a warning only, guard short rows, and guard the
empty sketchbook pool.

#### S3-7 (Low, SUSPECTED) - side-quest fallback ignores the source-tag and color-status gates

`util/AdventureQuestController.java:722-725`:

```java
if (validSideQuests.size > 0)
    ret = new AdventureQuestData(Aggregates.random(validSideQuests));
else
    ret = new AdventureQuestData(Aggregates.random(allSideQuests));
```

When every tagged quest is filtered out (all failed their `requiredColorStatus` gate added in round 107, or the
low-probability roll at `:717-719` rejected them all), the board hands out a random quest from the whole side-quest
pool, including quests meant for another color's capital and quests whose status requirement the player does not
meet. Upstream had the same fallback; the mod's new gates make it reachable in normal play (a board with one gated
quest). Fix: fall back to the tag-matched, gate-failing set with the gate ignored, or return "no work today".

#### S3-8 (Low, CONFIRMED) - the Deck Tester runs AI matches on background threads against shared engine state

`util/DeckTesterSimulator.java:78-238` starts a daemon "DeckTesterBatch" thread that creates and plays full `Match`es
while the live game keeps rendering. The engine's static state (`MyRandom`, `FModel`, `StaticData`, the card image
cache) is shared with the player's session, a timed-out game (`:152-178`) is abandoned by `shutdownNow()` but its
thread keeps mutating its `Game` until the AI notices the interrupt, and `gameRef.get().setGameOver(...)` at `:187`
is called from the batch thread while the game thread may still be inside it. Acceptable for a developer tool, but
it should refuse to run while a duel is in progress and should be documented as "desktop, dev builds only".

#### S3-9 (Low, CONFIRMED) - shop generation reseeds the world's shared random generator

`stage/MapStage.java:212,577,1352` (`WorldSave.getCurrentSave().getWorld().getRandom().setSeed(shopSeed)`) reseed the
one `World.random` that every mod system also draws from (capture rolls at `TerritoryControl.java:2164`, sack rolls,
chest events, Archaeologist loot, the second shop object's rarity roll at `:1209`). After a shop visit the sequence
of every subsequent "random" outcome is a pure function of that shop's seed and the number of draws since, so a
player who reloads, walks into the same shop and then fights the same mage gets the same capture roll every time.
Upstream did this too; the mod added most of the consumers. Fix: generate shop stock from a private
`new Random(shopSeed)` and leave the world generator alone.

#### S3-10 (Low, CONFIRMED) - the shop catalog objects are mutated per map

`ShopData.restockPrice` is written on the shared catalog instance every time a map loads (`stage/MapStage.java:1316`,
`:1343`) and by the chooser (`:208-209`), so the last town visited decides the restock price the next town of the
same shop type starts with until its own load overwrites it. Upstream pattern, extended by the chooser. Harmless
today because every load rewrites it before use; fragile for anything that reads `WorldData.getShopList()` outside
`loadObjects`.

#### Notes (not findings)

- Guard wages (`EconomyBuildings.java:2789-2830`), mine payouts (`:2731-2781`) and bank interest use integer week
  arithmetic with no overflow or negative-day risk for any reachable `currentDay`.
- `EditionProgression.restrictShopRewardsForCurrentTown` (`:382-441`) applies *no* restriction when the player has
  zero unlocked editions (empty list means "restrict = false" in `restrictToEditions` `:223`); unreachable today
  because the starter editions are unlocked at creation.
- `AdventureQuestController.loadData` sets `ignoreUnknownFields(true)` (`:387`) for quests.json only; every other
  loader throws on a typo, which is the better behavior for authored data (see S2-6 for the config files).
- Real-date coupling: `nextQuestDate` (`AdventureQuestController.java:692`) and the rotating-shop seed
  (`MapStage.java:1190`) use `LocalDate.now()`, so the wall clock, not the in-game day, throttles quest boards and
  rotates shops; a player who fast-forwards 40 in-game days in one sitting sees no rotation, one who sleeps on it
  does. Upstream behavior, listed under cross-cutting.

### 3.4 Pass 4 - Scenes, stages, actors and UI

Files read end to end: `stage/MapStage.java` (2091 lines), `stage/GameHUD.java`, `stage/GameStage.java`,
`stage/ConsoleCommandInterpreter.java`, `scene/DuelScene.java`, `character/EnemySprite.java`, `scene/TileMapScene.java`,
`scene/SaveLoadScene.java` (load and New Game+ paths), the item-use paths in `scene/InventoryScene.java` and
`GameHUD.setAbilityButton`, `util/KeyBinding.java`, and the remaining public surface of `player/AdventurePlayer.java`
(coin ransom, heal/defeat, ring gift, research, flags, quests). `forge-gui-mobile/src/forge/Adventure.java` for the
render loop.

#### S4-1 (High, CONFIRMED, upstream, reachable) - every map entered leaks its tileset textures

`scene/TileMapScene.java:219` and `:243`:

```java
map = new TemplateTmxMapLoader().load(Config.instance().getCommonFilePath(mapPath));   // POI entry
...
map = new TemplateTmxMapLoader().load(Config.instance().getFilePath(targetMap));      // sub-map transition
```

`TmxMapLoader` creates a new `Texture` for every tileset image of the map and hands ownership to the `TiledMap`
(`map.getOwnedResources()`); the only `map.dispose()` is in the scene's own `dispose()` (`:52-55`), which runs at
application exit. So each town, dungeon floor or cave entered uploads its tilesets again and never frees the previous
ones. Identical in the upstream base (`git show 042b3267af7:.../TileMapScene.java` lines 149/167), but this mod's
loop is "walk town to town all day": a session with 200 map entries holds 200 sets of tileset textures. Fix
(applied): dispose the previous `map` before loading the next one on both paths; nothing renders the old map between
the two statements, `MapStage.loadMap` drops every actor that referenced it, and the sign/overlay sprites come from the
atlas cache, not the map.

#### S4-2 (Medium, CONFIRMED, upstream) - `resetMapRecursive` loads whole maps to read their object lists and never disposes them

`stage/MapStage.java:1529-1564`: `loadMapFile()` parses each linked map (textures included) only to walk its `entry`
objects, and the `TiledMap` is dropped without `dispose()`. Reachable through `clearOnExit()` (the `reset map`
console command and `mustClearOnExit`). Fix (applied): dispose after the walk.

#### S4-3 (Low, CONFIRMED) - town defenders and Capitol mages get the day/night life modifier of world tile (0,0)

`scene/DuelScene.java:823-826`:

```java
if (this.eventData == null && !MapStage.getInstance().isInMap()) {
    int tileSize = Current.world().getTileSize();
    enemyStartingLife = Current.world().applyDayNightTerrainLife(enemyStartingLife,
            (int) enemy.getX() / tileSize, (int) enemy.getY() / tileSize);
```

`startTownAssault` (`stage/WorldStage.java:741`) and `startForcedCapitolDuel` (`:776-779`) build the duel's
`EnemySprite` without ever positioning it, so `getX()/getY()` are 0 and the modifier is read from the world's
south-west corner tile (whatever biome the generator put there), not the town's. The `[TFR-EnemyLife]` line
(`:832-836`) prints the adjusted value, so the log shows a modifier the design never asked for. Fix: position the
sprite at the town, or skip the terrain modifier for these two paths.

#### S4-4 (Low, CONFIRMED) - map entries naming enemies that do not exist spawn a random biome enemy instead

`stage/MapStage.java:959-965` substitutes a random enemy from the POI's biome when `WorldData.getEnemy(name)` returns
null (exact, case-sensitive match at `data/WorldData.java`). The plane's own maps do this 45 times
(`docs/review/2026-09-05-data-validation.txt`, category `ref-enemy`): `maps/map/skep/skep_outer.tmx` places 40
"Black/Blue/Green/Red/White Sliver" (the catalog names them `Sliver_Black` etc.) plus "Legionnaire",
`temple_of_liliana/bog.tmx` and `forest.tmx` three more Slivers, and two forts a "Human Guard". The Skep hive is
therefore populated by random biome roamers rather than Slivers, with only a stderr line to say so.

#### S4-5 (Low, CONFIRMED) - quest and map flags are bytes

`player/AdventurePlayer.java:2480-2494` and `stage/MapStage.java:1994-2012` store every flag as a `byte`;
`advanceCharacterFlag`/`advanceQuestFlag`/`advanceMapFlag` add without a cap, so any counter that reaches 128 turns
negative and every `>=` condition on it fails. The mod already caps `townsRestored` at 127
(`util/TownRestoration.java:441`); `capturedFrom_<color>` (max 5 per color), `guardHired`, `mineBuilt` and the rest
cannot reach the limit today. Upstream design; worth a comment where the cap lives.

#### S4-6 (Low, CONFIRMED) - the render loop hides every NullPointerException and IllegalStateException

`forge-gui-mobile/src/forge/Adventure.java:76-79` (upstream) swallows both exception types per frame with no log.
The mod's day tick, territory resolution and dialogs run inside that frame, so a bug in any of them presents as a
scene that stops responding or a frame that skips rendering, with nothing in `forge.log`. Fix: log the first
occurrence of each distinct stack trace (a small `Set<String>` keyed on the top frames) - still no crash, but the
`[TFR-*]` diagnostics culture this project relies on regains its most important signal.

#### S4-7 (Low, ergonomics) - notifications are shown strictly one after another, 15 s each

`stage/GameHUD.java:1322-1369` queues each notification with `Actions.after(...)`, so a day tick that produces five
capture/sack/guard messages shows the last one about 75 s later, long after the event, and a burst of ten (a bad
week under Speed-Up) takes two and a half minutes to drain. The `[RED]` disband and life-bonus lines are in that
queue. Fix: coalesce same-tick notifications into one panel, or shorten the hold when more are waiting.

#### Notes (not findings)

- `Controls.newTextButton` and `UIActor.onButtonPress` root-guard `isDisabled()`; the comment on the Capitol toll
  dialog (`WorldStage.java:1000-1008`) still describes the pre-fix framework behavior.
- The debug console (F9/F10, avatar long-press on Android) ships enabled with `give gold`, `teleport`, `set charflag`
  etc.; upstream behavior, but for a released game it deserves a settings toggle or at least a
  `[TFR-Console]` log line per command so a suspicious save can be explained.
- Key bindings are the fixed table in `util/KeyBinding.java:11-28` (arrows/WASD, I, Q, E, M, F4, B); there is no
  rebinding UI and `E` is bound to both Deck and Equip. Accessibility gap, not a bug.
- `MapStage.getReward()` (`:1759-1764`) has a misleading indentation (`enemies.remove(currentMob)` sits under an `if`
  but is not inside it); the behavior is correct.
- `GameHUD.refreshMiniMap()` uploads a 2800 x 2800 texture once per in-game day (about every 12 real seconds under
  Speed-Up); it disposes the previous texture correctly, so this is a hitch, not a leak.
- `WorldStage.onActing` calls `TerritoryControl.onMageArrived` while iterating `enemies` with an explicit iterator;
  safe today because arrival never spawns.

### 3.5 Pass 5 - Engine, launcher and asset-pipeline edits outside the adventure package

Reviewed as `git diff 042b3267af7..HEAD` over `forge-game`, `forge-core`, `forge-ai`, `forge-gui/src`,
`forge-gui-mobile/src/forge/{Forge.java,assets,screens}`, `forge-gui-android`, `forge-gui-mobile-dev` and
`forge-gui-desktop`: 57 files, 509 insertions, 81 deletions, 22 of them launcher icons. Every edit is small, commented
with its date and reason, and behavior-preserving for non-Adventure clients:

- `forge-game/.../Game.java` `rerollAnte()` (+95 lines): re-selects the ante under the game's own rules, remembers
  the last 10 rejected names, prefers Uncommon-or-better, bounded to 10 attempts. Correct and self-contained; the
  cost side of the feature is where the defect is (S3-1).
- `Player.initVariantsZones` / `RegisteredPlayer`: starting permanents that begin tapped, placed exactly like the
  untapped list. Correct.
- `Zone.java:242-251`: `List.of()` replaced by a mutable list because a caller sorts the result in place. This is a
  genuine upstream bug (confirmed by the crash the comment cites) and should be sent upstream.
- `FControlGameEventHandler` / `IGuiGame.revealAnteCards` (default delegates to `reveal`) / `MatchController`
  override gated on `Forge.isMobileAdventureMode`: network play and desktop are untouched.
- `AutoUpdater.attemptToUpdate`: `if (true) return false;` disables the Swing updater for the fork. Works; a
  named constant (`FORK_PINS_ENGINE = true`) would read better and silence the "unreachable code" lint.
- `AssetsDownloader`: retargets APK/assets URLs to the fork's releases, relaxes the version-stamp guard to a
  "proven mismatch" test. Correct; the release-process corollary in its comment (build APK and assets.zip in one
  Maven run) belongs in `ANDROID_RELEASE.md` too.
- `ForgeProfileProperties`: own data dirs (`ForsakenRealms/…`, `.forsakenrealms`), card art shared with stock
  Forge's cache on desktop only. Deliberate; note that the *stock* Forge cache dir is created by this game when
  absent, which is a small footprint outside the game's own folder.
- `ForgePreferences`: ante on by default. `PlaybackSpeed`: extra SUPERFAST tier, labels rotated correctly.
- `AbstractPreferences.save()`: creates the parent directory first (fixes a first-launch preference loss).
- Android: own package id, Sentry disabled (`io.sentry.auto-init=false`, providers removed), file-provider authority
  renamed to match `Main.java`, `GitLogs` tag prefix `tfr-v`. Consistent across the four files that must agree.
- `TransitionScreen.withEnemyStatKey`: separates the display label from the statistics key. Correct.

No defects found in this pass beyond S3-1's cost handling.

### 3.6 Pass 6 - Data and asset integrity, licensing, tests, tooling and repository hygiene

#### Data validation (script: `scratchpad/validate_data.py`, report: `docs/review/2026-09-05-data-validation.txt`)

The script parses every JSON file in the plane with libGDX's lenient rules (comments, unquoted keys, newline-separated
members, raw newlines in strings), checks every key against the Java loader class it is read into, parses all 779
atlases and the 349 maps reachable from the plane's 335 POIs (following `teleport` links; all 349 maps in the plane
are reachable, none is dead), and cross-checks references. Results:

| Check | Result |
|---|---|
| JSON parse failures | 0 |
| Unknown keys vs loader classes (`ConfigData`, `TuningData`, `PointOfInterestData`, `EnemyData`, `ShopData`, `ItemData`, `RewardData`, `DialogData`/`ActionData`/`ConditionData`, `BiomeData`, `WorldData`, `DifficultyData`, `EffectData`, `ArmoryRarityData`, `SpawnTierWeightData`, quest stages) | 0 |
| Atlas page images missing | 0 of 779 atlases |
| Atlas regions referenced but missing | 1 (`RandomShop`, S3-5) |
| POI `map`/`spriteAtlas`/`sprite` references | all resolve |
| Biome enemy and POI lists | all resolve |
| Quest item, enemy-tag, POI-tag, prerequisite and `issueQuest` references | all resolve |
| Enemy decks (1,877 references, `.dck` and `.json` generators) | all resolve; every `.dck` has a non-empty `[Main]` |
| Enemy reward items | 33 distinct missing names, 56 references (S3-2) |
| Reward `type` values | 1 bad (`"Card"`, S3-3) |
| Map enemies | 45 placements name unknown enemies (S4-4) |
| Map shop lists | 7 slots name unknown shops (S3-4) |
| Map templates / tilesets / teleport targets | all resolve within the reachable set |
| Duplicate names (items, enemies, POIs, shops, quest ids, stage ids) | none |

`points_of_interest.json` uses `type` values `sidebosseasy/moderate/hard` on 17 entries; the code only tests for
`town`, `capital` and the map-name heuristics, so these are informational.

#### Licensing

`LICENSE` (GPL v3) at the root, `README.md` credits Forge and the ported planes, `standalone-packaging/CREDITS.md`
credits the Forge, Realm of Legends, Shandalar Old Border, Shandalar, Innistrad and Amonkhet teams,
`docs/Credit-and-Thanks.md` carries the upstream artist/music attributions, and `build_standalone.py` copies
`LICENSE.txt`, `CONTRIBUTORS.txt`, `README.md` and `CREDITS.md` into the package and mirrors `LICENSE.txt` +
`CREDITS.md` into the plane folder (`:470-476`). The user-supplied art (cave icons, splash images, resource sheets,
Rally rune) is not attributed anywhere; if any of it is not the author's own work it needs a line in `CREDITS.md`.
The Sentry DSN that shipped in upstream's manifest is removed, so no telemetry leaves the fork. Card imagery is
fetched from Card-Forge's CDN at runtime, which the packaging notes acknowledge.

#### Tests and static analysis

- The repository has 118 test classes, all upstream engine tests; there are **0** tests for anything under
  `forge.adventure`, and none for the mod's arithmetic-heavy systems (territory pull sources, capture odds, guard
  wages, edition shards, save round-trips). The three highest-value tests to add are a save/load round-trip of
  `SaveFileData` with every pinned class, a `TerritoryControl.onMageArrived` table test for the ownership outcomes
  (S2-3/S2-4), and a data-validation test that runs the checks above in CI.
- Checkstyle covers imports only; no SpotBugs/PMD/ErrorProne. `javac -Xlint:all` over the mobile module reports
  559 warnings (`docs/review/2026-09-05-phase0-javac-lint-mobile.log`), 13 over core; the `[serial]` warnings that
  matter were pinned in this round (S1-3/S1-4), the rest are raw types and unchecked casts in upstream code.
- Six `catch (Exception)` blocks with empty bodies remain in the adventure tree (156 catch sites total); the
  important ones are listed individually (S1-1, S1-6, S2-6, S3-6).

#### Reproducible builds and packaging

- `build_standalone.py` verifies jar-vs-base-install version, refuses an engine-date mismatch, derives the file list
  from `git diff` against the working tree and stamps `res/.base_install_version`. It does **not** record the git
  commit that was packaged; `config.json`'s `modVersion`/`modVersionDate` are hand-edited. A `git rev-parse HEAD`
  written next to `build.txt` would make a live-folder or APK traceable to a commit.
- The Maven build is deterministic given the same JDK (Oracle 22.0.2 here, compiler plugin 3.8.1, `--release 17`);
  the packaging depends on an external stock-Forge install of the matching daily, which is documented but not
  pinned by hash.
- `ContentFilterTables` writes CSVs into the packaged plane folder at first start (S3-6), so a packaged folder is not
  byte-identical after one launch.

#### Repository hygiene

- Working tree clean apart from this review's `docs/review/`. No build outputs, logs or saves are tracked; the
  tracked `.jar` files are upstream's Android libs.
- `.claude/settings.json` is **tracked** and sets `"defaultMode": "bypassPermissions"`; anyone who clones the repo
  and opens it in Claude Code inherits a permissionless agent by default. Move it to `.claude/settings.local.json`
  (git-ignored) or drop the key.
- The `config tables/*.csv` runtime outputs are tracked for `items.csv`/`enemies.csv` but `expansions.csv` is not,
  so a run from the repo can dirty the tree.
- Commit history is one commit per round with long messages; the review's untracked `docs/review/` folder is
  committed as part of this round.

## 4. Cross-cutting observations

**4.1 Native resource lifecycle is the systemic weakness.** libGDX `Pixmap`, `Texture` and `TiledMap` free memory
only on `dispose()`, and the code base treats them like garbage-collected objects: the minimap re-bake (S2-1), every
ground tile ever composited (S2-2), every map entered (S4-1) and every map read for its object list (S4-2) leaked
before this round. Three of the four are upstream habits that the mod's play pattern (long sessions, hundreds of
town visits, daily territory repaints) turns from "unnoticed" into "the process grows by hundreds of MB". The fixes
are all local, but the *habit* needs a rule: whoever calls `new Pixmap`/`new Texture`/`TmxMapLoader.load` owns the
result until it is handed to something with a documented `dispose()`, and every mod method that returns a Pixmap
says in its Javadoc whether the caller owns it. The Java heap never shows these leaks; a periodic
`[TFR-Mem]` line printing `Gdx.app.getNativeHeap()` and `getJavaHeap()` would have exposed all four in the first
playtest.

**4.2 Silent failure is the default error policy.** A corrupt or mismatched save regenerates the world (S1-1), a
typo in `config.json` disables every feature (S2-6), a missing item, enemy, shop type or sprite falls back to nothing
or to a random substitute (S3-2, S3-4, S3-5, S4-4), a read-only install disables the content filter (S3-6), and the
render loop hides `NullPointerException` outright (S4-6). Each fallback was reasonable for a hobby engine; together
they mean the game keeps running while quietly doing something other than what the data says, and the only witness
is `forge.log`. The project's `[TFR-*]` logging is excellent for mechanics; the same discipline applied to *data and
load failures* (one `[TFR-DataError]` per distinct problem, surfaced once in the HUD) would close most of this.

**4.3 Identity by mutable string.** `PointOfInterest.getID()` is derived from the POI's current template name, so
changing ownership changes identity, and every per-town map (`pointOfInterestChanges`, territory radii, despawn days,
guard records, the Rally rune's last target) is keyed by that id. The consequences are S2-3, S2-4 and the "save grows
by one orphan per capture" note, and every future feature that stores per-town state inherits the trap. A stable
per-POI id (position-derived, assigned at world generation and stored) with the template name kept as a separate
mutable field would remove the whole class of bugs; the migration is a one-time re-key on load.

**4.4 Shared mutable globals.** One `World.random` serves world generation, shop stock (which reseeds it, S3-9),
capture and sack rolls, chest events and loot; `MyRandom` serves names, gold variance and enemy picks; the `ShopData`
catalog is mutated per map (S3-10); static maps in `TerritoryControl` outlive the save (S2-5). None of this is a
crash risk, but it makes outcomes replayable in ways players will discover, and it makes every "why did X happen"
question in a log harder to answer than it should be.

**4.5 Threading is simpler than it looks.** Almost everything runs on the libGDX render thread; the exceptions are
world generation (`CompletableFuture` fan-outs that complete before the stage starts), the Forge game thread (which
calls back into `AdventurePlayer` for the ante re-roll, S3-1, and into `GameEnd` on the EDT), and the Deck Tester's
daemon threads (S3-8). No data race with observable consequences was found; the ante re-roll's problem is ordering,
not concurrency. The one rule worth writing down: `AdventurePlayer` is render-thread state, and anything that
mutates it from the game thread must also update the in-match copy.

**4.6 Time has two clocks.** In-game days drive territory, economy, quests and rotation; wall-clock dates drive quest
board throttling and rotating shops (`LocalDate.now()`), and real seconds drive roaming-monster lifetimes and
notification pacing. Speed-Up makes the first clock run 50x while the others do not, which is why notifications back
up (S4-7) and why quest boards feel stuck after a fast week.

**4.7 Save-format governance.** After this round every serializable class in the save is pinned, but the save still
has no format version number and no migration hook; compatibility is guaranteed only by never removing or retyping a
field. The `DecompressibleInputStream` trick that substitutes local class descriptors is a good safety net for
added fields and a silent-corruption risk for anything else. Recommend a `saveFormatVersion` int written by
`WorldSave.save()` and checked in `load()` before any object is read.

**4.8 Upstream merge friction.** The mod keeps almost all logic in new files (`TerritoryControl`, `EconomyBuildings`,
`TownRestoration`, `EditionProgression`, `DungeonRotation`, `ResourceSpawns`, `ChestEvents`, `QuestExpiry`, …) and
marks every edit inside upstream files with a dated comment, which is the right shape. The friction is concentrated
in five upstream files that have grown large mod regions: `World.java` (4,356 lines, about half mod),
`WorldStage.java`, `MapStage.java`, `AdventurePlayer.java` and `RewardScene.java`. The v1.06 engine merge will
conflict there first; splitting the mod regions of `World.java` (fog of war, territory painting, minimap baking,
standings history) into helper classes before the merge would cut that risk sharply and is mechanical work.

**4.9 Diagnostics.** The `[TFR-*]` line convention, the `[TFR-DayTick]` timing line and the `[TFR-Perf]` repaint
timings are the best part of the code base for maintainability; they made most of this review's confirmations
possible from code alone. The gaps: no memory line (4.1), no data-error line (4.2), and no one-line summary at load
time of what the save contained (day, towns owned, quests active, format pins) to anchor a log review.

## 5. Prioritized remediation plan

Blast radius: **local** = one method or one data file; **subsystem** = one of the mod's systems and its save fields;
**wide** = save format, upstream files or every scene. Merge friction: **none** = mod-only file; **low** = a few
lines inside an upstream file, already marked; **high** = changes the shape of an upstream method.

### Fix now (applied in this round - round 123 - and packaged into the live folder)

| ID | Change | Blast radius | Merge friction |
|---|---|---|---|
| S2-1 | Dispose the previous `biomeImage` in `rebakeMinimapAfterTerritoryControl` and `generateNew` | local | low (mod region of `World.java`) |
| S2-2 | `getBiomeSprite` returns caller-owned pixmaps (copy of the fog tile, edge tile drawn into the fresh pixmap, `hazeTile` disposes its input); `WorldBackground` disposes after `draw` at both sites | subsystem (terrain rendering) | low |
| S2-3 | Remove `TOWN_RESTORED_FLAG` from the town's changes when an AI mage takes or sacks a player town, before `transformInto` | local | none |
| S2-5 | `TerritoryControl.resetSessionState()` clears the pull-source fingerprints, re-contest days and the neutral-defense tally; called from `WorldStage.clearCache()` | local | none |
| S3-1 | `MatchController.revealAnteCards` also charges the in-match player's mana shards through `DuelScene.chargeInGameManaShards` | local | low |
| S4-1 | `TileMapScene.load(...)` disposes the previous `TiledMap` on both load paths | local | low |
| S4-2 | `MapStage.resetMapRecursive` disposes each map it parsed | local | low |
| S1-2 | `WorldSave.save()` also catches `RuntimeException`, restores the `.old` backup and reports | local | low |
| S1-3, S1-4 | `serialVersionUID` pinned on `SaveFileData`, `DialogData.ActionData.QuestFlag`, `AdventureEventData.AdventureEventHuman` at their current derived values | wide (save format, no behavior change) | low |
| S2-7 | `BiomeStructure` failure branch loops over the chunk, not the whole map | local | low (upstream file) |

### Fix before the next release (v1.06)

| ID | Change | Blast radius | Merge friction |
|---|---|---|---|
| S1-1 | On a world-load failure: keep the `.old` backup (do not delete it on the next autosave), log `[TFR-Load] WORLD REGENERATED` with the exception, and show a blocking dialog before the player can continue. Keep regeneration only as an explicit "recover" choice. | subsystem (save/load) | low |
| S2-4 | Re-key `pointOfInterestChanges` (and the id-keyed maps on `World`) in `transformInto`: move the entry to the new id when the town keeps its player/AI record, delete ownership-only flags otherwise; add a `[TFR-Orphan]` audit on load | wide (save contents) | low |
| S2-6 | Fail loudly when the plane's own `config.json`/`settings.json` exists but does not parse (startup dialog, refuse New Game) | local | none |
| S3-2, S3-3, S3-4, S3-5, S4-4 | Data fixes from the validator: add or drop the 33 reward items, `"Card"` -> `"card"`, rename the Sliver/guard enemies in the three maps or add catalog entries, add the `Horror`/`Everything` shop types or fix the two maps, add a `RandomShop` region | local (data) | none |
| S4-6 | Log the first occurrence of each distinct exception swallowed by `Adventure.render` | local | low (upstream file) |
| S3-6 | Compute content-filter exclusions before writing the CSVs; guard short rows and the empty sketchbook pool | local | none |
| S6-1 | Untrack `.claude/settings.json` or drop `bypassPermissions` from it | local | none |
| 4.7 | Write a `saveFormatVersion` into the save and check it on load | wide | low |
| 4.1 | Add a `[TFR-Mem]` native/Java heap line to the day tick | local | none |

### Eventually

| ID | Change | Blast radius | Merge friction |
|---|---|---|---|
| S1-5 | Key quest timers by quest instance, not template id | subsystem | none |
| S1-6 | Log the exception in `WorldStage.load` | local | low |
| S1-7, S3-9 | Seed a private generator per subsystem from the world seed; stop reseeding `World.random` for shops | subsystem | low |
| S3-7 | Side-quest fallback that respects the gates | local | low |
| S3-8 | Refuse to start a Deck Tester batch during a duel; mark the tool dev-only | local | none |
| S3-10 | Copy `ShopData` before setting `restockPrice` | local | low |
| S4-3 | Position assault/defense sprites at the town before the duel, or skip the terrain modifier | local | none |
| S4-5 | Document the 127 cap on byte flags where `advance*Flag` lives | local | low |
| S4-7 | Coalesce same-tick notifications | local | none |
| S6-2 | Add the three tests named in 3.6 (save round-trip, capture outcomes, data validation in CI) | none | none |
| S6-3 | Stamp the packaged build with `git rev-parse HEAD` | local | none |
| 4.3 | Stable per-POI ids | wide | low |
| 4.8 | Split `World.java`'s mod regions into helpers before the engine merge | wide (mechanical) | reduces future friction |

## 6. Open questions for the author

1. **Sacked and captured towns.** When an AI takes a player town, should the buildings, guards, bank balance and
   reputation stored under the old id be destroyed, frozen until re-liberation, or carried over to the new owner? Today
   they are orphaned and come back only through the name round-trip (S2-4). The right answer decides the S2-4 fix.
2. **World regeneration on a failed load.** Is there any case where silently regenerating the world for an existing
   player is what you want (S1-1)? If not, the fallback should become an explicit recovery option.
3. **Ante re-roll cost in events.** With S3-1 fixed, ordinary duels now really charge shards; Inn events with
   `allowsShards == false` never did. Intended?
4. **Wall-clock throttles.** Quest boards and rotating shops use the real date. With Speed-Up in the HUD, should they
   move to in-game days (4.6)?
5. **Debug console in the shipped game.** Keep it (upstream does), gate it behind a setting, or log its use?
6. **The 33 missing reward items (S3-2).** Were they removed from `items.json` on purpose (unwanted equipment) or
   lost in the catalog curation? The fix differs (drop the reward vs. restore the item).
7. **Key bindings.** Is a fixed table (and `E` bound to both Deck and Equip) acceptable for release, or is a
   rebinding screen in scope?
8. **Card art in stock Forge's cache.** Sharing the folder is deliberate; creating stock Forge's cache directory on a
   machine that never had Forge is a side effect worth confirming.
9. **User-supplied art.** Is every image under `ui/`, `sprites/` and `maps/tileset/` that is not from Forge or the
   ported planes the author's own work? If not, `CREDITS.md` needs the attribution before the next release.

## 7. Minor / cosmetic

- `stage/WorldStage.java:1000-1008` - the Capitol-toll comment describes the pre-2026-08-25 button behavior; the root
  guard in `Controls.newTextButton` now covers it.
- `stage/MapStage.java:1759-1764` - `enemies.remove(currentMob)` is indented under an `if` it is not part of.
- `forge-gui/.../AutoUpdater.java` - `if (true) { return false; }`; a named constant reads better and avoids the
  unreachable-code lint.
- `util/KeyBinding.java:19,21` - `E` is bound to both `Deck` and `Equip`.
- `world/points_of_interest.json` - 17 entries use `type` values `sidebosseasy/moderate/hard` that no code reads.
- `world/enemies.json` entry 685 (Falco Spara) rewards the literal placeholder item `"Name of Item"`.
- `world/enemies.json` entries 16, 54, 304 use lowercase `colors` (`uw`, `brw`, `b`); the code upper-cases, so this
  is harmless, but every other entry is uppercase.
- `data/ItemData.java:49-65`, `data/DialogData.java:148-185`, `data/EffectData.java:41-50` - copy constructors with
  no callers (S1-9); `ItemData(ItemData)` would NPE on a null `effect` if anyone used it.
- `util/ContentFilterTables.java:131-136` - `KNOWN_UNUSED_ITEMS` lists sketchbooks as "Currently Unused" while
  `landSketchbookShop` sells them.
- `stage/ConsoleCommandInterpreter.java:691,695` - the `sprint` and `remove enemy nearest` commands answer
  "removed all enemies".
- `scene/DuelScene.java:981-988` (`applyAdventureDeckRules`) - hard-coded 10/15 attraction/contraption sizes
  with a TODO; upstream.
- `docs/review/2026-09-05-data-validation.txt` - keep the validator in `dev-tools/` and run it before each packaging
  round; it takes four seconds.

---

## Appendix A - Phase 0: boundary, build and orientation map

### A.1 Boundary (empirical)

| Item | Value |
|---|---|
| `origin` | https://github.com/TheSAguy/The-Forsaken-Realms.git (branch `main`, pushed as `master`) |
| `upstream` | https://github.com/Card-Forge/forge.git |
| HEAD | `612418069cc` Round 122 (2026-09-05) |
| `git merge-base HEAD upstream/master` | `042b3267af7` "Crash Fix when restarting on Android (#11786)" = Forge daily 09.05 |
| Mod commits not in upstream | 299 (`git rev-list --count upstream/master..HEAD`) |
| `git diff --shortstat 042b3267af7..HEAD` | 2627 files changed, 457,496 insertions, 524 deletions |
| Added / modified / deleted | 2487 A, 139 M, 1 D (`forge-gui-android/publish.bat`) |

Where the delta lives:

| Area | Files | Notes |
|---|---|---|
| `forge-gui/res/adventure/The Forsaken Realms/**` | 2427 added | the plane: 1430 `.dck`, 349 `.tmx`, 295 `.atlas`, 293 `.png`, 33 `.json`, 7 `.tsx`, 6 `.txt`, 4 `.mp3`, 3 `.tx`, 2 `.jpg`, 2 `.md`; 26 MB tracked |
| `forge-gui/res/adventure/common/**` | 3 modified | `ui/items.json`, `ui/items_portrait.json`, `custom_cards/tibalt_boss_effect.txt` |
| `forge-gui-mobile/src/forge/adventure/**` | 77 modified + 25 added Java | the mod's engine work; 61,297 lines total in the package after the mod |
| `forge-gui-mobile/src/forge/{Forge,screens/*,assets/*,toolbox/*}` | 6 modified | app-dir sniff, ante reveal/reroll, transition screen, updater, card panel |
| `forge-game/src/main/java/forge/game/**` | 4 modified | `Game.java` (+95: ante rarity/reroll memory), `Player.java`, `RegisteredPlayer.java` (tapped start cards), `Zone.java` |
| `forge-gui/src/main/java/forge/**` | 8 modified | `IGuiGame` default hook, `FControlGameEventHandler` ante reveal, `PlaybackSpeed.SUPERFAST`, `AutoUpdater` disabled, profile-dir rebrand, prefs |
| `forge-gui-android/**`, `forge-gui-mobile-dev/**`, `forge-gui-desktop/**` | launcher, manifest, icons | `Main.java`, `GitLogs.java`, `GameLauncher.java`, `HelpMenu.java` |
| Repo root / tooling | `CLAUDE.md`, `MOD_*.md`, `CORE_ENGINE_CHANGES.md`, `ANDROID_RELEASE.md`, research notes, `standalone-packaging/`, `dev-tools/save-editing/`, `.claude/settings.json`, `.github/workflows/test-build.yaml`, `.gitignore` | |

In-scope Java for the review = every file under `forge-gui-mobile/src/forge/adventure` (whether or not the
mod touched it, per the brief) plus every other Java file in the diff: **178 files**. The full list is the
Phase 1 inventory. `dev-tools/save-editing/*.java` are offline tools and are reviewed only for the save
format assumptions they encode.

### A.2 Build

Toolchain: Apache Maven 3.9.16 (`C:\Users\User\.claude\Tools\apache-maven-3.9.16`), Oracle JDK 22.0.2
(`C:\Program Files\Java\jdk-22`), Windows 11. `pom.xml` compiles with `maven-compiler-plugin` 3.8.1 and
`<release>17</release>` (`forge-gui-mobile/pom.xml` overrides with `source/target 17` and no `release`);
libGDX 1.14.2 (`forge-gui-mobile/pom.xml`). No `-Xlint`, `showWarnings` or `showDeprecation` in any pom.

Static analysis wired into the build: only upstream's `maven-checkstyle-plugin` at the `validate` phase with
`failOnViolation=true`, and `checkstyle.xml` contains exactly two checks (`UnusedImports`, `RedundantImport`).
No SpotBugs / PMD / Error Prone. Tests: `forge-game` has 2 test files; `forge-gui-mobile` has none, and no
test anywhere references the adventure package. The mod's CI (`.github/workflows/test-build.yaml`) runs
`mvn -U -B clean test` under Xvfb on a Java matrix.

Production build used by the project (recorded; ran clean this session before the review started):

```
mvn -pl forge-gui-mobile-dev -am package -DskipTests -o        # BUILD SUCCESS, 16:07 min, jar 67.7 MB
```

Review build (forces a full recompile of the three modules the mod edits, with all warnings on):

```
rm -rf forge-game/target/maven-status forge-gui/target/maven-status forge-gui-mobile/target/maven-status
mvn -pl forge-gui-mobile -am compile -o -Dmaven.compiler.showWarnings=true \
    -Dmaven.compiler.showDeprecation=true -Dmaven.compiler.compilerArgument=-Xlint:all
```

Result: **BUILD SUCCESS in 8:08 min** (forge-core 152, forge-game 805, forge-ai 191, forge-gui 440,
forge-gui-mobile 392 source files recompiled). Full log: `docs/review/2026-09-05-phase0-build.log`.
The `-Xlint:all` argument did NOT reach javac (maven-compiler-plugin 3.8.1 has no user property for
`compilerArgument`; the log's "Recompile with -Xlint:unchecked for details" notes prove it), so the
Maven run only yields the deprecation pass: 48 warnings, of which these are in scope -
`adventure/data/EffectData.java:59,81` (`Array(Class)` deprecated), `adventure/data/RewardData.java:469`
(`Date.getYear()` twice), `adventure/player/AdventurePlayer.java:1463` (`Array.toArray(Class)`),
`adventure/util/Selector.java:110,116` (`Pools`), and four `StringUtils.containsIgnoreCase` deprecations
in `forge-gui/.../ForgeProfileProperties.java:194,210,224,234` (upstream lines in a mod-edited file).

To get the real lint pass, javac was run directly against the built jar-with-dependencies:

```
javac -Xlint:all -Xmaxwarns 100000 -encoding UTF-8 --release 17 -proc:none -cp <jar-with-dependencies> -d <scratch> @<all 392 forge-gui-mobile sources>
    # jar = forge-gui-mobile-dev/target/forge-gui-mobile-dev-2.0.15-SNAPSHOT-jar-with-dependencies.jar; exit 0, 559 warnings, 26 s
javac -Xlint:all ... @<the 12 mod-changed forge-game/forge-gui files>   # exit 0, 13 warnings
```

Logs: `docs/review/2026-09-05-phase0-javac-lint-mobile.log`, `docs/review/2026-09-05-phase0-javac-lint-core.log`.
Of the 559 mobile warnings, **289 are in in-scope files** (270 are upstream-only `forge.screens/*`,
`forge.toolbox/*`, etc. and are not reviewed): rawtypes 80, unchecked 77, **serial 57**, this-escape 29,
static 15, fallthrough 14, deprecation 7, lossy-conversions 5, try 4, cast 1. By file: `scene/UIScene.java` 52,
`player/AdventurePlayer.java` 30, `world/World.java` 17, `screens/match/MatchController.java` 16,
`pointofintrest/PointOfInterestChanges.java` 15, `util/UIActor.java` 14, `util/Controls.java` 10,
`stage/WorldStage.java` 9. The 13 core warnings are all on upstream lines (`this-escape` in `Game`/`Player`/
`RegisteredPlayer` constructors, `serial` on `Zone.game` from 2017, the `containsIgnoreCase` deprecations).

Warning classes that are review input rather than noise (traced in Phase 2, listed here so they are not lost):

- `[serial]` - save-format surface. Serializable classes with **no `serialVersionUID`**:
  `util/SaveFileData.java:15` (the top-level object of every save file, read by a plain
  `ObjectInputStream` in `WorldSave.load()`), `pointofintrest/PointOfInterestChanges.java:94` (inner class
  `Map`), `data/DialogData.java:61` (`QuestFlag`), `data/AdventureEventData.java:1158` (`AdventureEventHuman`),
  `world/SpritesDataMap.java:17` (`BiomeSpriteDataMap`). Non-transient fields of non-serializable type in
  serializable classes: 27 in `AdventurePlayer` (`:58-138,183,291,462`), 6 in `AdventureQuestStage`,
  4 in `AdventureQuestData`, 5 in `AdventureQuestController`, 2 each in `WorldData`, `BiomeData`,
  `AdventureEventData`, 1 each in `AdventureEventController:94`, `WorldSaveHeader:19` (the `Pixmap` preview),
  `BiomeTexture:26`. `SaveFileData.java:179` declares a public `Object readObject(String)` that javac flags as
  a mis-shaped serialization hook (harmless but confusing).
- `[fallthrough]` 14: `stage/MapStage.java:1199`, `data/AdventureQuestData.java:246,263`,
  `scene/AdventureDeckEditor.java:261`, `stage/Console.java:111`, `scene/EventScene.java:531`,
  `scene/MenuScene.java:286-295` (4), `util/Config.java:386,404,417`, `screens/match/MatchController.java:434`.
- `[lossy-conversions]` 5: `stage/GameStage.java:615`, `scene/RewardScene.java:1147-1148`,
  `util/RewardActor.java:750,752` (float->int compound assignments in layout math).
- `[try]` 4: `world/WorldSave.java:284-285,298-299` explicit `close()` inside try-with-resources.
- `[rawtypes]`/`[unchecked]` 157: concentrated in `UIScene`/`UIActor` (JSON-driven UI reflection),
  `AdventurePlayer` and `SaveFileData` consumers (`(Map<String,Integer>) readObject(...)` casts) - each
  unchecked cast on a save-file value is a place where an older save's shape can surface as a
  `ClassCastException` at load; Phase 2 checks them individually.

### A.3 Module layout (Maven reactor order)

`forge-core` (cards/decks/items model) -> `forge-game` (rules engine) -> `forge-ai` -> `forge-gui`
(shared GUI-agnostic layer: preferences, `IGuiGame`, localizer, `res/`) -> `forge-gui-mobile` (libGDX
app: `forge.Forge`, `forge.screens.*` match UI, **`forge.adventure.*`**) -> `forge-gui-mobile-dev`
(LWJGL3 desktop launcher `forge.app.GameLauncher`, produces the shipped jar-with-dependencies) and
`forge-gui-android` (`forge.app.Main`). `forge-gui-desktop` is the Swing client (unused by the mod except
one menu URL).

Adventure package layout (`forge-gui-mobile/src/forge/adventure`):

| Package | Files | Lines | Role |
|---|---|---|---|
| `character` | 13 | 2,468 | scene-graph actors on maps: `PlayerSprite`, `EnemySprite`, `ShopActor`, `QuestActor`, `RewardSprite`, `PortalActor`, `OnCollide`... |
| `data` | 31 | 5,129 | JSON-bound POJOs: `WorldData`, `BiomeData`, `PointOfInterestData`, `EnemyData`, `ItemData`, `RewardData`, `AdventureQuestData/Stage`, `AdventureEventData`, `DialogData`, `ConfigData`, mod-new `TuningData`, `SpawnTierWeightData`, `ArmoryRarityData`, `RestrictedCardsData`, `SettingData`... |
| `player` | 2 | 2,974 | `AdventurePlayer` (inventory, decks, currencies, flags, save/load) |
| `pointofintrest` | 3 | 845 | `PointOfInterest` (placed POI), `PointOfInterestChanges` (per-POI persistent state), `PointOfInterestMap` |
| `scene` | 31 | 13,868 | one `Scene` per screen; `UIScene` base (JSON-described UI via `UIActor`), `TileMapScene`, `RewardScene`, `DuelScene`, `ArenaScene`, `EventScene`, mod-new `ResearchScene`, `WorldStandingsScene`, `InfoTextScene`, `MenuScene` |
| `stage` | 13 | 8,165 | libGDX stages: `WorldStage` (overworld), `MapStage` (tmx interiors), `GameHUD`, `WorldBackground` (mod-new), `ConsoleCommandInterpreter`, `MapSprite`, `PointOfInterestMapSprite` |
| `util` | 42 | 18,232 | loaders and systems: `Config` (plane + file resolution), `Current`, `CardUtil`, `AdventureQuestController`, `AdventureEventController`, `SaveFileData`, `MapDialog`, `Controls`; mod-new systems `TerritoryControl` (2,650), `EconomyBuildings` (2,879), `TownRestoration` (1,338), `DungeonRotation`, `ColorReputation`, `EditionProgression`, `ChestEvents`, `ResourceSpawns`, `SpawnTierWeighting`, `QuestExpiry`, `ContentFilterTables`, `DeckTesterSimulator`, `ArmoryRarity`... |
| `util/pathfinding` | 8 | 768 | A* for enemy movement (upstream) |
| `world` | 12 | 8,848 | `World` (4,355 lines; generation + territory state + fog + save), `WorldSave`, `WorldSaveHeader`, `BiomeTexture`, `BiomeSprites`, `BiomeStructure`, `OpenSimplexNoise`, `Model` (WFC) |

### A.4 Entry points and screen lifecycle

- Desktop: `forge-gui-mobile-dev/src/forge/app/GameLauncher.java` builds the LWJGL3 config (mod: window
  title "The Forsaken Realms (Forge <ver>)", window icons from `res/skins/default/adv_icon_*.png`) and
  starts `forge.Forge`. Android: `forge-gui-android/src/forge/app/Main.java`.
- `forge.Forge` owns the libGDX `ApplicationListener`; adventure mode is `Forge.isMobileAdventureMode`,
  rendered through `forge.Adventure` (`Adventure.render(delta)` -> `Forge.currentScene.render()` +
  `act(delta)`, with a screenshot-based cross-fade on scene swaps). Profile/data dirs are rebranded
  (`ForgeProfileProperties`: `%APPDATA%\ForsakenRealms`, `~/.forsakenrealms`, `~/Library/Application Support/ForsakenRealms`).
- Scenes: `Forge.switchScene(Scene)` (`forge-gui-mobile/src/forge/Forge.java:1073-1100`) calls
  `currentScene.leave()` (may veto), pushes it on the `lastScene` stack (deduplicating an already-present
  scene by truncating the stack), takes a screenshot for the transition, sets `Adventure.sceneWasSwapped`,
  and calls `newScene.enter()`. `Forge.switchToLast()` (`:1106`) pops. Scenes are singletons created once
  (`XScene.instance()`), never disposed between visits; `UIScene` builds its widgets from `ui/<name>.json`
  (+ `_portrait` variant) via `UIActor`.
- **Context note (upstream code, `forge-gui-mobile/src/forge/Adventure.java:80-84`)**: the whole per-frame
  `render()/act()` of the current scene runs inside
  `catch (IllegalStateException | NullPointerException ie) { //silence this.. //TODO: Don't silence this. }`.
  Any NPE the mod throws from a scene's `act`/`render` is swallowed every frame; the game keeps running
  with whatever state the exception left behind. This shapes the error-surfacing review: mod-side NPEs
  in scene code are silent wrong-game, not crashes.
- Overworld: `WorldStage` (extends `GameStage`) hosts `PlayerSprite`, roaming `EnemySprite`s (incl.
  Territory Control capture mages with `territoryTarget`), POI sprites, `WorldBackground` (biome
  texture + fog of war). Entering a POI: `WorldStage.loadPOI(poi)` -> `TileMapScene` -> `MapStage.loadMap(tmx)`
  builds actors from Tiled object layers (`shop`, `enemy`, `reward`, `inn`, `quest`, `portal`, `entry`...).
  Combat: `DuelScene` wraps a `forge.game` match via `MatchController`; results return through
  `DuelScene.afterGameEnd()` -> `AdventureQuestController`, `ColorReputation`, `TerritoryControl`.
- Per-day systems tick from `WorldStage` (mod: `EconomyBuildings`, `TerritoryControl.processDaysPassed`,
  `DungeonRotation`, `QuestExpiry`, fog; instrumented with `[TFR-Perf]` `nanoTime` logs).

### A.5 Data-loading pipeline

- `Config.instance()` picks the plane (`settings.json` in the profile dir, `SettingData`), resolves
  `res/adventure/<plane>/` with `../common/` fall-through via `getFile()/getFilePath()` (`util/Config.java:320-358`),
  and loads `config.json` (`ConfigData`), `config tables/*.json` (`TuningData`, `RestrictedCardsData`,
  `SpawnTierWeightData`, `ArmoryRarityData`) on demand.
- World data: `world/world.json` -> `WorldData` (`World.java:556`), which pulls `biomes/*.json`
  (`BiomeData`, each referencing `sprites/map_sprites.json` -> `BiomeSprites`), `enemies.json`
  (`EnemyData[]`), `shops.json` (`ShopData[]`), `items.json` (`ItemListData`), `points_of_interest.json`
  (`PointOfInterestData[]`), `quests.json` (`AdventureQuestController`, the only loader with
  `setIgnoreUnknownFields(true)`), `heroes.json` (`HeroListData`), `ui/*.json` (`UIActor`).
- Every loader is a bare `new Json().fromJson(...)`. **Verified from the libGDX 1.14.2 binary
  (`javap -c com.badlogic.gdx.utils.Json.<init>`): `ignoreUnknownFields` is never set, so it is `false`.**
  Consequence: a JSON key with no matching field throws `SerializationException` at load, i.e. "keys the
  loader ignores" cannot exist in any file that currently loads except `quests.json`; the converse
  (required keys omitted) is the real risk, and it surfaces as default-valued fields, not errors.
- Maps: Tiled `.tmx` under `maps/**` loaded by `MapStage` (libGDX `TmxMapLoader`), object properties
  read by string key with `prop.containsKey(...)` guards in most places; `.tx` files are Tiled object
  templates (`maps/obj/{research_lab,stone,wood}.tx`).
- Atlases: libGDX `.atlas` text files; POI sprites via `Config.getPOISprites(data)` =
  `getAtlas(data.spriteAtlas).createSprites(data.sprite)` (all same-named regions = variants), item
  icons via `Config.getItemSprite(name)` from `sprites/items.atlas`, which is also the glyph atlas of the
  Textra font (`util/Controls.java:669-677`), so every item icon name doubles as a text glyph.

### A.6 Save pipeline

- `WorldSave.save()` (`world/WorldSave.java:262-307`): `DeflaterOutputStream` -> `ObjectOutputStream`,
  writes `WorldSaveHeader` (Java-serialized, UID pinned `7676320057945211217L`, holds a `Pixmap` preview)
  then one `SaveFileData` (`extends HashMap<String, byte[]>`; every value is an independently
  Java-serialized object) with four sub-blobs: `player` (`AdventurePlayer`), `world` (`World`),
  `worldStage` (`WorldStage`: roaming enemies incl. territory mages), `pointOfInterestChanges`.
- `WorldSave.load()` mirrors it (`:77-121`). `SaveFileData`'s custom `ObjectInputStream`
  (`util/SaveFileData.java:340-360`) **overrides `serialVersionUID` mismatches with the local class
  descriptor** ("Overriding serialized class version mismatch"), which is the mechanism behind the stock
  "Data Migration completed" dialog; the mod pinned UIDs on ten save-bound classes in round 90 after a
  derived-UID change wiped inventories. Phase 2 traces every stored key, every enum written, and every
  class that is still Java-serialized without a pin.
- `World` and `PointOfInterest` persist through `SaveFileContent.load/save` (key/value), not raw
  serialization; `AdventurePlayer` stores `ItemData[]`/`EffectData` objects whole (Java serialization).

### A.7 Threading map (first pass; verified list, adventure package)

- No `synchronized` block or method anywhere in `forge/adventure`.
- World generation `World.generateNew(long seed)` (`world/World.java:1003-~1905`, one ~900-line method)
  fans out with `CompletableFuture.supplyAsync` on the common pool three times (`:1094-1126`,
  `:1670-1704`, `:1707-1748`) while a single seeded `java.util.Random` (`:57`, seeded `:1019`) is the
  world RNG - determinism and data-race review item.
- `util/DeckTesterSimulator.java` creates a batch `Thread` and a single-thread `ExecutorService` per
  simulated game (`:78`, `:135-178`), reporting back with `Gdx.app.postRunnable`.
- `scene/EventScene.java:621` `AtomicInteger pending` around AI-vs-AI simulation; `scene/DuelScene.java:300`
  `Gdx.app.postRunnable` end-of-match; `FThreads.invokeInEdtNowOrLater` in `ArenaScene`, `GameStage`,
  `MapStage:1945`, `ConsoleCommandInterpreter`, `WorldStage:462,764,785,807`, `EconomyBuildings:893`,
  `BiomeTexture:49`; `AdventureDeckEditor.java:771` `invokeInBackgroundThread`.

### A.8 Randomness map (first pass; verified list)

Seeded: `World.random` (world seed), `Model` (WFC, `Model.java:199`), `RewardData` (world RNG unless
`useSeedlessRandom`), `AdventureEventController:123` (event seed), `PointOfInterestChanges:339` (shop seeds),
`MapStage:1191` (rotating shop seed), `RubbleOverlay:35` (fixed 99).
Unseeded `new Random()`: `AdventureEventData:62,113` (fallback when `eventSeed<=0`), `AdventureQuestStage:153`
(target variance), `ArenaScene:53`, `NewGameScene:61`, `Config:665`, `AdventureQuestController:271,718`
(quest offer rolls), `AdventureEventController:202` (next event date, also `LocalDate.now()`), `World:57`
(re-seeded in `generateNew`; whether it is used before that is a Phase 2 check). Wall clock in
generation-adjacent code: `BiomeStructure:56`, `World:998-1102,1901` (timing only), `DeckTesterSimulator:152,165` (timeouts).

### A.9 Observations logged during orientation (to be confirmed or dropped in Phase 2)

- `.claude/settings.json` is committed with `"defaultMode": "bypassPermissions"` and `.gitignore` was
  changed to track it: every clone hands an AI agent permission-bypass by default (git hygiene).
- `forge-gui/src/main/java/forge/download/AutoUpdater.java` is neutralized with `if (true) { return false; }`
  (dead code below it; upstream-merge friction).
- Two deck files carry non-ASCII names (`decks/legends/clavileño.dck`, `márton_stromgald.dck`) - Windows /
  Android / zip encoding and lookup by card name are Phase 2 checks.
- `Adventure.render()` swallow (upstream) - see A.4.
- Checkstyle is import-only; no adventure tests; no static analysis.
- Data validation is entirely runtime: a malformed JSON file throws from `Config`/`WorldData` static
  initializers at plane load (Phase 2 will trace what the player sees).

---

## Appendix B - Phase 1 inventory

Legend: Status A = added by the mod (+lines), M = modified (+added/-deleted vs the upstream merge base), U = upstream file
unchanged by the mod but inside the adventure package. Deps = in-scope classes this file references (first four + count);
Used-by = number of in-scope files referencing this class. Signals = first-pass regex counts: sw catch(Exception/Throwable),
rnd unseeded Random, thr threading primitives, ser serialization markers, smut non-final static fields, todo TODO/FIXME/HACK,
lint javac -Xlint:all hits. Risk = first-pass review priority (Critical = owns save format or can corrupt a save), not a finding.

| Pass | Subsystem | Files | Lines | Mod-added lines | Critical | High | Medium | Low |
|---|---|---|---|---|---|---|---|---|
| 1 | Save/load & persisted model | 28 | 10,266 | 2,876 | 14 | 7 | 3 | 4 |
| 2 | World generation & territory | 27 | 16,549 | 10,095 | 2 | 7 | 6 | 12 |
| 3 | Economy, towns, rewards, decks | 35 | 14,453 | 8,681 | 0 | 11 | 7 | 17 |
| 4 | Scenes, stages, actors, UI | 65 | 20,184 | 3,167 | 0 | 6 | 20 | 39 |
| 5 | Core-engine / launcher / asset edits | 23 | 14,953 | 467 | 0 | 2 | 11 | 10 |
| 6 | Plane data files (res/adventure/The Forsaken Realms/**, validated as its own pass) | 2427 | - | - | - | - | - | - |

#### Pass 1 - Save/load & persisted model

| File | Status | Lines | Deps | Used-by | Signals | Risk | Purpose / mod delta |
|---|---|---|---|---|---|---|---|
| `adventure/player/AdventurePlayer.java` | M +1092/-9 | 2,787 | AdventureDeckEditor, AdventureEventController, AdventureEventData, AdventureModes +46 | 43 | sw5 ser69 todo3 lint30 | **Critical** | Player state: inventory, decks, currencies, flags, statistics; custom key/value save + Java-serialized item objects. Mod (+1092). |
| `adventure/data/AdventureEventData.java` | M +182/-11 | 1,292 | AdventureEventController, AdventureOverrides, AdventurePlayer, Config +16 | 11 | sw1 rnd2 ser10 lint4 | **Critical** | Serializable Inn tournament / event model (participants, matches, rewards). Mod (+182): AI simulation, ante removal. |
| `adventure/util/AdventureQuestController.java` | M +101/-8 | 742 | AdventureEventData, AdventureQuestData, AdventureQuestEvent, AdventureQuestEventType +24 | 18 | rnd2 ser2 smut1 lint6 | **Critical** | Serializable quest registry + runtime: loads quests.json, active/completed quests, event dispatch (updateQuestsWin/Leave...), nav targets, quest offers. Mod (+101). |
| `adventure/data/AdventureQuestData.java` | M +104/-18 | 581 | AdventureQuestController, AdventureQuestEvent, AdventureQuestStage, Current +14 | 13 | ser4 lint6 | **Critical** | Serializable quest definition + runtime state. Mod (+104): giverColor, requiredColorStatus, expiry. |
| `adventure/data/AdventureQuestStage.java` | M +148/-3 | 551 | AdventureQuestController, AdventureQuestData, AdventureQuestEvent, AdventureQuestEventType +12 | 6 | sw2 rnd1 ser2 lint6 | **Critical** | Serializable quest objective stage. Mod (+148): nav filters, target variance, Ring City exclusions. |
| `adventure/pointofintrest/PointOfInterestChanges.java` | M +317/-0 | 507 | Current, EconomyBuildings, EnemyData, MapStage +6 | 26 | ser34 lint15 | **Critical** | Per-POI persistent state: map flags, shop seeds, economy buildings, guards. Mod (+317). |
| `adventure/world/WorldSave.java` | M +108/-3 | 369 | AdventureModes, AdventurePlayer, Config, DifficultyData +17 | 44 | sw1 ser2 lint4 | **Critical** | Save/load orchestration: header + SaveFileData blobs (player, world, worldStage, poiChanges), autosave/quicksave, backups. Mod (+108). |
| `adventure/util/SaveFileData.java` | U | 363 | Forge | 12 | ser7 lint5 | **Critical** | HashMap<String,byte[]> of Java-serialized values; custom ObjectInputStream tolerating UID mismatches. Upstream, unchanged. |
| `adventure/util/AdventureEventController.java` | M +80/-7 | 336 | AdventureEventData, AdventureOverrides, AdventurePlayer, Current +4 | 8 | rnd1 ser2 smut1 lint1 | **Critical** | Serializable Inn-event scheduler: per-POI next event dates, event seeds, creation. Mod (+80). |
| `adventure/pointofintrest/PointOfInterest.java` | M +67/-1 | 222 | AdventureQuestStage, Config, Current, DialogData +6 | 29 | ser3 | **Critical** | A placed POI (position, sprite index, active flag, display name). Mod (+67): transformInto, active/despawn, sprite spread. |
| `adventure/data/DialogData.java` | M +69/-0 | 213 | AdventurePlayer, AdventureQuestController, ColorReputation, ConsoleCommandInterpreter +10 | 16 | ser9 lint2 | **Critical** | Serializable dialog tree + action model. Mod (+69): grantRingGift, console-command actions, QuestFlag. |
| `adventure/data/EffectData.java` | M +16/-2 | 141 | Forge, ItemData, Zone | 8 | sw2 ser2 lint2 | **Critical** | Serializable item effect (in every inventory ItemData); UID pinned round 90. |
| `adventure/data/ItemData.java` | M +14/-1 | 99 | Config, DialogData, EffectData, Forge +2 | 20 | ser2 | **Critical** | Serializable inventory item. Mod (+14): quest-item / general-sale flags, glyph description. |
| `adventure/world/WorldSaveHeader.java` | M +3/-0 | 54 | Serializer | 2 | sw1 ser4 smut1 lint1 | **Critical** | Serializable save header (name, date, Pixmap preview); UID pinned. |
| `adventure/data/RewardData.java` | M +247/-24 | 612 | AdventureEventController, AdventurePlayer, AdventureQuestController, ArmoryRarity +16 | 26 | rnd1 ser2 smut2 todo1 lint2 | **High** | Serializable reward roll model. Mod (+247): wood/stone, rarity gates, seeded RNG selection. |
| `adventure/data/BiomeData.java` | M +121/-9 | 256 | AdventureQuestController, BiomeStructureData, BiomeTerrainData, ContentFilterTables +7 | 20 | ser2 lint2 | **High** | Biome JSON model (terrain, structures, spawns). Mod (+121): territory recolor and Ring City data. |
| `adventure/data/EnemyData.java` | M +60/-0 | 199 | ArenaScene, BiomeData, CardUtil, Config +7 | 25 | ser2 | **High** | Serializable enemy definition. Mod (+60): noAnte, tiers, life/speed, territory fields. |
| `adventure/player/PlayerStatistic.java` | M +10/-0 | 189 | AdventureEventData, AdventurePlayer, AdventureQuestController, DuelScene +4 | 3 | ser4 lint2 | **High** | Win/loss statistics (SaveFileContent). Mod (+10). |
| `adventure/data/WorldData.java` | M +16/-0 | 125 | BiomeData, BiomeSprites, Config, ContentFilterTables +5 | 18 | ser2 smut2 lint6 | **High** | Serializable world.json model. Mod (+16). |
| `adventure/world/SpritesDataMap.java` | U | 123 | BiomeSpriteData, BiomeSprites, SaveFileContent, SaveFileData | 1 | ser6 lint6 | **High** | Chunked map-sprite index (SaveFileContent); inner Serializable map class. |
| `adventure/pointofintrest/PointOfInterestMap.java` | U | 119 | PointOfInterest, SaveFileContent, SaveFileData | 1 | lint4 | **High** | Chunked POI index (SaveFileContent). |
| `adventure/util/QuestExpiry.java` | A +109 | 110 | AdventureQuestData, Config, ConfigData, Current +4 | 4 | - | **Medium** | Mod-new: side-quest day timers. |
| `adventure/data/PointOfInterestData.java` | M +3/-0 | 83 | BiomeData, Config, DialogData, Paths | 11 | ser2 smut1 lint1 | **Medium** | JSON model: the information for the point |
| `adventure/data/BiomeSpriteData.java` | M +9/-0 | 54 | BiomeSprites, SaveFileContent, SaveFileData | 4 | - | **Medium** | JSON model: the information for the |
| `adventure/util/Serializer.java` | U | 84 | - | 1 | - | **Low** | Abstract class to serialize other objects. |
| `adventure/util/AdventureQuestEvent.java` | U | 24 | AdventureQuestData, AdventureQuestEventType, EnemySprite, ItemData +1 | 3 | - | **Low** | Quest event record (type, POI, enemy, count) posted to the quest controller. |
| `adventure/util/AdventureQuestEventType.java` | U | 21 | - | 3 | - | **Low** | Enum of quest event kinds (Win, Lose, Leave, ItemUsed, ...). |
| `adventure/util/SaveFileContent.java` | U | 10 | SaveFileData | 9 | - | **Low** | Interface to save the content of the save game file |
#### Pass 2 - World generation & territory

| File | Status | Lines | Deps | Used-by | Signals | Risk | Purpose / mod delta |
|---|---|---|---|---|---|---|---|
| `adventure/world/World.java` | M +3458/-62 | 4,356 | AdventurePlayer, AdventureQuestData, BiomeData, BiomeSpriteData +41 | 34 | sw9 rnd1 thr13 ser41 lint17 | **Critical** | World state and generation: 900-line generateNew(), biome/terrain arrays, territory maps, fog of war, day clock, key/value save. Mod (+3458). |
| `adventure/stage/WorldStage.java` | M +1245/-13 | 1,797 | AdventurePlayer, AdventureQuestController, AdventureQuestData, AdventureQuestStage +53 | 34 | sw3 thr4 ser16 smut1 lint9 | **Critical** | Overworld stage: player/enemy actors, POI entry, day tick dispatch to mod systems, enemy persistence incl. territory mages. Mod (+1245). |
| `adventure/util/TerritoryControl.java` | A +2650 | 2,651 | AdventurePlayer, BiomeData, ChestEvents, ColorReputation +26 | 22 | smut5 | **High** | Mod-new: AI colors expand territory, dispatch capture mages, capture/sack towns, guard fights, roads, standings, victory/defeat. |
| `adventure/util/Config.java` | M +140/-6 | 742 | AdventureModes, AdventureOverrides, ArmoryRarity, ArmoryRarityData +17 | 77 | sw7 rnd1 smut1 todo2 lint3 | **High** | Plane selection, path resolution (../common fall-through), atlas/sprite caches, config-table loaders. Mod (+140). |
| `adventure/util/ResourceSpawns.java` | A +453 | 454 | AdventurePlayer, ChestEvents, ColorReputation, Config +18 | 6 | smut2 | **High** | Mod-new: overworld gold/wood/stone/shard pickups, caps, respawn. |
| `adventure/world/BiomeTexture.java` | M +3/-0 | 409 | BiomeData, BiomeStructure, BiomeStructureData, BiomeTerrainData +1 | 1 | thr1 ser3 lint1 | **High** | class that will create auto tiles and render the biomes in chunks |
| `adventure/stage/WorldBackground.java` | M +215/-0 | 399 | DungeonRotation, GameStage, MapSprite, MapStage +4 | 4 | lint6 | **High** | Overworld background actor: biome texture chunks + fog of war + discovery flash. Mod +215. |
| `adventure/util/DungeonRotation.java` | A +385 | 386 | AdventureQuestController, AdventureQuestData, AdventureQuestStage, Config +11 | 9 | smut2 | **High** | Mod-new: rotatable dungeon pool - despawn on clear/defeat, natural expiry, reserve activation. |
| `adventure/util/ColorReputation.java` | A +364 | 365 | AdventurePlayer, Config, ConfigData, DuelScene +6 | 25 | - | **High** | Mod-new: per-color reputation scoring and status tiers (War..Partner). |
| `adventure/scene/WorldStandingsScene.java` | A +526 | 527 | ColorReputation, Config, Controls, Current +15 | 5 | smut1 | **Medium** | Mod-new: World Standings page. |
| `adventure/data/TuningData.java` | A +233 | 234 | AdventurePlayer, ColorReputation, Config, ConfigData +15 | 11 | - | **Medium** | Mod-new: tunable balance numbers loaded from config tables/settings.json. |
| `adventure/world/Model.java` | U | 226 | ColorMap | 2 | smut3 | **Medium** | Wave-function-collapse base model (seeded Random) used for structure generation. |
| `adventure/util/SpawnTierWeighting.java` | A +219 | 220 | AdventureQuestController, BiomeData, ColorReputation, Config +11 | 11 | - | **Medium** | Mod-new: 3-layer weighted overworld spawn tiers. |
| `adventure/world/BiomeStructure.java` | M +23/-2 | 164 | BiomeStructureData, ColorMap, Config, OverlappingModel +1 | 2 | sw1 | **Medium** | Runs the WFC model for a biome structure; wall-clock timeout. Mod (+23). |
| `adventure/data/ConfigData.java` | M +91/-0 | 132 | BiomeData, ContentFilterTables, DifficultyData, EditionProgression +9 | 28 | - | **Medium** | Plane config.json model. Mod (+91): feature flags (dungeonRotationEnabled, editionProgressionEnabled, ...). |
| `adventure/world/OpenSimplexNoise.java` | U | 2,427 | - | 1 | - | **Low** | OpenSimplex noise implementation (seeded) for terrain. |
| `adventure/world/SimpleTiledModel.java` | U | 334 | ColorMap, Model | 0 | - | **Low** | WFC simple-tiled variant. |
| `adventure/world/OverlappingModel.java` | U | 294 | ColorMap, Model | 2 | - | **Low** | WFC overlapping variant. |
| `adventure/data/BiomeStructureData.java` | M +1/-0 | 69 | - | 4 | - | **Low** | JSON model for a biome structure (WFC source image, sizes). Mod (+1). |
| `adventure/world/ColorMap.java` | U | 56 | - | 4 | - | **Low** | Pixmap-backed color lookup helper for biome maps. |
| `adventure/data/SettingData.java` | M +21/-0 | 54 | CardUtil, DeckTesterSimulator, EventScene, Game | 6 | - | **Low** | JSON model: settings outside of the cho |
| `adventure/util/Paths.java` | M +12/-0 | 50 | WorldStage | 22 | - | **Low** | Hard-coded resource paths. Mod (+12). |
| `adventure/world/BiomeSprites.java` | M +8/-1 | 48 | BiomeSpriteData, Config | 3 | - | **Low** | class to load and buffer map sprites |
| `adventure/data/SpawnTierWeightData.java` | A +42 | 43 | ColorReputation, RestrictedCardsData, SpawnTierWeighting | 3 | - | **Low** | Backing class for the plane's "config tables/spawn_tier_weighting.json" (user request 2026-08-23: week-based t |
| `adventure/util/Current.java` | U | 43 | AdventurePlayer, Config, Forge, InventoryScene +3 | 52 | smut1 | **Low** | Global accessors (Current.player()/world()). |
| `adventure/data/DifficultyData.java` | M +6/-0 | 37 | BiomeData | 10 | - | **Low** | JSON model: the information for the diffi |
| `adventure/data/BiomeTerrainData.java` | U | 32 | BiomeData | 3 | - | **Low** | JSON model: the information for the terra |
#### Pass 3 - Economy, towns, rewards, decks

| File | Status | Lines | Deps | Used-by | Signals | Risk | Purpose / mod delta |
|---|---|---|---|---|---|---|---|
| `adventure/util/EconomyBuildings.java` | A +2879 | 2,880 | AdventurePlayer, ArenaScene, CardUtil, ColorReputation +43 | 24 | thr1 ser4 smut1 | **High** | Mod-new: rebuildable economy buildings (Bank, Mines, Trader/Exchange, Outlook, Teleporter, Armory...) and their dialogs/payouts. |
| `adventure/util/RewardActor.java` | M +43/-0 | 1,447 | AdventurePlayer, Config, Controls, EconomyBuildings +11 | 4 | sw14 smut1 todo2 lint6 | **High** | Card/item reward widget with image fetch. Mod (+43). |
| `adventure/util/TownRestoration.java` | A +1338 | 1,339 | AdventurePlayer, BiomeData, ColorReputation, Config +26 | 27 | sw1 smut3 | **High** | Mod-new: wasteland town restoration, Capitol upgrade and migration, Ring gifts, player-town ownership. |
| `adventure/scene/RewardScene.java` | M +559/-16 | 1,268 | AdventurePlayer, CardUtil, Config, Controls +20 | 21 | sw1 ser1 smut4 todo1 lint3 | **High** | Shop / reward screen incl. Armory family buttons, restock. Mod (+559). |
| `adventure/util/CardUtil.java` | M +169/-12 | 1,138 | AdventureReadPriceList, Config, ConfigData, Current +11 | 11 | sw2 todo1 | **High** | Deck generation and card filtering for enemies/shops. Mod (+169). |
| `adventure/scene/ArenaScene.java` | M +618/-3 | 1,032 | AdventurePlayer, AdventureQuestController, ArenaData, ChestEvents +27 | 8 | rnd1 thr3 smut1 | **High** | Arena bracket screen. Mod (+618). |
| `adventure/scene/EventScene.java` | M +328/-6 | 992 | AdventureEventController, AdventureEventData, AdventurePlayer, AdventureQuestController +24 | 7 | thr4 smut3 todo3 lint1 | **High** | Inn tournament screen incl. AI simulation. Mod (+328). |
| `adventure/util/EditionProgression.java` | A +504 | 505 | AdventurePlayer, ArmoryRarity, ColorReputation, Config +19 | 22 | - | **High** | Mod-new: progressive set unlocks per color, shard-gated research. |
| `adventure/character/ShopActor.java` | M +253/-3 | 329 | ColorReputation, Config, EconomyBuildings, Forge +12 | 9 | - | **High** | Shop building actor. Mod (+253): economy buildings, fixed shops, teleporter animation, Ring price multiplier. |
| `adventure/util/ChestEvents.java` | A +318 | 319 | ArenaData, ArenaScene, CardUtil, ColorReputation +21 | 4 | - | **High** | Mod-new: chest pickup random events (1-of-6) + blueprint roll. |
| `adventure/util/DeckTesterSimulator.java` | A +240 | 241 | ArenaScene, Game, MatchController, RegisteredPlayer | 4 | sw2 thr19 | **High** | Mod-new: AI-vs-AI batch simulation on worker threads with per-game executor and timeout. |
| `adventure/scene/SpellSmithScene.java` | M +69/-4 | 594 | CardUtil, ColorReputation, Config, ConfigData +15 | 3 | smut1 | **Medium** | Spell Smith card-purchase scene (filters, edition pools). Mod (+69). |
| `adventure/scene/InnScene.java` | M +186/-6 | 361 | AdventureEventController, AdventureEventData, AdventurePlayer, ColorReputation +18 | 6 | smut5 | **Medium** | Scene for the Inn in towns |
| `adventure/util/ContentFilterTables.java` | A +324 | 325 | Config, ConfigData, EnemyData, Forge +3 | 9 | sw3 smut3 | **Medium** | Mod-new: CSV-driven content filters (bans/allow lists) in the plane folder. |
| `adventure/scene/ResearchScene.java` | A +312 | 313 | AdventurePlayer, Config, Controls, Current +10 | 5 | smut2 | **Medium** | Mod-new: Research Lab (set unlocks) screen. |
| `adventure/util/Reward.java` | M +25/-1 | 127 | ItemData | 18 | - | **Medium** | Reward class that may contain gold,cards or items |
| `adventure/util/ArmoryRarity.java` | A +110 | 111 | ArmoryRarityData, Config, ConfigData, PointOfInterestChanges +4 | 4 | - | **Medium** | Mod-new: time/venue-gated Armory rarity. |
| `adventure/data/ItemListData.java` | M +28/-0 | 67 | Config, ContentFilterTables, ItemData, Paths | 10 | smut1 lint1 | **Medium** | Item registry loader. Mod (+28): general-sale filter, sketchbooks. |
| `adventure/util/AdventureReadPriceList.java` | U | 168 | Config, Forge, ForgeConstants, Paths +1 | 1 | - | **Low** | Reads card price lists for Adventure mode. |
| `adventure/util/AdventureOverrides.java` | U | 151 | ConfigData, Forge | 3 | sw2 | **Low** | Plane-scoped overrides for block and booster data. |
| `adventure/util/TimeOfDayActor.java` | A +117 | 118 | Controls, GameHUD, MapStage, World +1 | 3 | lint1 | **Low** | Mod-new: HUD day/time widget. |
| `adventure/util/ResourceDisplayActor.java` | A +107 | 108 | AdventurePlayer, Config, Controls, EconomyBuildings +3 | 3 | lint1 | **Low** | Mod-new: HUD wood/stone readout. |
| `adventure/scene/ShopScene.java` | U | 103 | AdventureDeckEditor, AdventurePlayer, Current, DeckEditScene +5 | 2 | smut1 | **Low** | DeckEditScene scene class that contains the Deck editor |
| `adventure/scene/ShardTraderScene.java` | U | 98 | Controls, Current, Forge, GameHUD +2 | 3 | smut1 | **Low** | Scene for the Shard Trader in towns |
| `adventure/data/HeroListData.java` | M +9/-0 | 82 | BiomeData, Config, ConfigData, Forge +2 | 5 | smut1 | **Low** | JSON model: the a list of all heroes |
| `adventure/util/RubbleOverlay.java` | A +48 | 49 | TimeOfDayActor | 2 | smut1 | **Low** | Mod-new: procedural rubble visual (fixed seed). |
| `adventure/data/ArmoryRarityData.java` | A +45 | 46 | ArmoryRarity, Config, EditionProgression, SpawnTierWeightData | 2 | - | **Low** | Armory item-rarity weights by venue and in-game week - see the plane's "config tables/armory_rarity.json" for  |
| `adventure/data/ShopData.java` | U | 26 | RewardData, SettingData | 10 | - | **Low** | JSON model: data for a Shop on the map |
| `adventure/data/RaceShopData.java` | A +24 | 25 | AdventurePlayer, HeroListData, MapStage, RaceEditionData +1 | 2 | - | **Low** | One race's assigned starting SHOP TYPES (user spec 2026-08-30: "Depending on the race. |
| `adventure/data/GeneratedDeckData.java` | U | 20 | BiomeData, GeneratedDeckTemplateData, RewardData | 1 | - | **Low** | JSON model: the information for a generat |
| `adventure/data/RestrictedCardsData.java` | A +15 | 16 | Config, ConfigData, RewardData, TuningData | 2 | - | **Low** | Backing class for the plane's "config tables/restricted_cards.json" (user request 2026-08-22: "create a Restri |
| `adventure/data/GeneratedDeckTemplateData.java` | U | 15 | BiomeData | 2 | - | **Low** | JSON model: the information for the gener |
| `adventure/data/HeroData.java` | U | 15 | BiomeData | 1 | - | **Low** | JSON model: the information possible hero |
| `adventure/data/RaceEditionData.java` | A +13 | 14 | AdventurePlayer, HeroListData | 5 | - | **Low** | One race's assigned starting expansions (MOD_SCOPE.md #4 extension, user spec 2026-08-12: "assign each race a  |
| `adventure/data/ArenaData.java` | U | 11 | RewardData | 2 | - | **Low** | JSON model for Arena settings. |
#### Pass 4 - Scenes, stages, actors, UI

| File | Status | Lines | Deps | Used-by | Signals | Risk | Purpose / mod delta |
|---|---|---|---|---|---|---|---|
| `adventure/stage/MapStage.java` | M +811/-38 | 2,092 | AdventurePlayer, AdventureQuestController, ArenaScene, CharacterSprite +57 | 48 | sw5 thr1 smut1 todo3 lint2 | **High** | Interior (tmx) stage: object-layer actor construction, dungeon clear/exit, shop overlays, dialogs. Mod (+811). |
| `adventure/stage/GameHUD.java` | M +237/-4 | 1,378 | AdventurePlayer, AdventureQuestController, AdventureQuestData, CharacterSprite +29 | 32 | smut1 | **High** | HUD stage: minimap, notifications, ability buttons, resources, mage markers. Mod (+237). |
| `adventure/scene/DuelScene.java` | M +414/-18 | 1,178 | AdventureEventController, AdventureEventData, AdventurePlayer, ArenaScene +38 | 19 | sw1 thr4 smut1 todo2 | **High** | Wraps a forge.game match; result routing to quests, reputation, territory. Mod (+414). |
| `adventure/stage/ConsoleCommandInterpreter.java` | M +261/-0 | 828 | AdventureEventController, AdventureEventData, BiomeData, CardUtil +29 | 6 | sw18 thr2 smut1 | **High** | Debug console + item commandOnUse dispatcher (teleports, give, spawn...). Mod (+261). |
| `adventure/scene/UIScene.java` | M +51/-4 | 794 | Config, Controls, DeckSelectScene, EventScene +12 | 20 | sw2 smut1 lint52 | **High** | JSON-described UI scene base (ui/*.json), dialogs, z-order. Mod (+51). |
| `adventure/character/EnemySprite.java` | M +116/-1 | 788 | AdventurePlayer, CharacterSprite, ColorReputation, Config +22 | 23 | todo1 lint1 | **High** | Roaming or map enemy actor with steering; Territory Control mage fields (territoryColor, territoryTarget), tier labels, defeat dialogs. Mod +116. |
| `adventure/scene/AdventureDeckEditor.java` | U | 1,165 | AdventureEventController, AdventureEventData, AdventurePlayer, Config +4 | 4 | thr1 smut2 todo4 lint3 | **Medium** | Adventure deck editor built on FDeckEditor (collection, auto-sell, background thread). |
| `adventure/util/Controls.java` | M +11/-0 | 828 | AdventurePlayer, ArenaScene, Config, Current +2 | 35 | sw6 smut4 lint10 | **Medium** | Widget factory in the mod's style; Textra font with item glyph atlas. Mod (+11). |
| `adventure/stage/GameStage.java` | M +63/-3 | 827 | CharacterSprite, Config, Controls, Current +22 | 14 | thr1 smut2 lint2 | **Medium** | Base class to render a player sprite on a map used for the over world and dungeons |
| `adventure/util/MapDialog.java` | M +111/-3 | 672 | AdventurePlayer, AdventureQuestData, CharacterSprite, Config +18 | 16 | sw2 smut1 todo3 lint4 | **Medium** | Dialog tree runner (conditions, actions). Mod (+111). |
| `adventure/scene/NewGameScene.java` | M +107/-8 | 666 | AdventureModes, AdventurePlayer, Config, Current +18 | 5 | rnd1 smut1 | **Medium** | NewGame scene that contains the character creation |
| `adventure/scene/InventoryScene.java` | M +70/-1 | 629 | AdventurePlayer, AdventureQuestController, Config, ConsoleCommandInterpreter +10 | 5 | sw1 smut1 todo1 lint5 | **Medium** | Inventory / equipment scene: use, equip, sell, delete. Mod (+70). |
| `adventure/scene/MapViewScene.java` | M +205/-17 | 547 | AdventureEventData, AdventurePlayer, AdventureQuestData, Config +9 | 7 | smut1 todo1 | **Medium** | Displays the rewards of a fight or a treasure |
| `adventure/scene/SettingsScene.java` | M +57/-6 | 546 | Config, Controls, Forge, ForgeConstants +6 | 2 | thr3 smut1 lint1 | **Medium** | Scene to handle settings of the base forge and adventure mode |
| `adventure/scene/SaveLoadScene.java` | M +43/-4 | 518 | AdventurePlayer, Config, Controls, Current +13 | 4 | sw3 ser1 smut1 lint1 | **Medium** | Scene to load and save the game. |
| `adventure/util/UIActor.java` | U | 414 | Config, Controls, Forge, KeyBinding +3 | 4 | sw1 lint14 | **Medium** | Reflection-driven widget group built from UIData. |
| `adventure/character/CharacterSprite.java` | M +25/-1 | 366 | Config, DialogData, DuelScene, EnemyData +3 | 11 | lint1 | **Medium** | Animated map actor base (walk/attack rows). Mod (+25): extra animation rows / facing. |
| `adventure/scene/MenuScene.java` | M +44/-0 | 364 | AdventurePlayer, CharacterSprite, Controls, Current +8 | 2 | todo2 lint5 | **Medium** | MenuScene Superclass for menu scenes which do not have HUD but need dialog functionality |
| `adventure/scene/StartScene.java` | M +16/-0 | 291 | Config, ConfigData, Controls, FSkinTexture +13 | 5 | sw1 smut1 lint1 | **Medium** | First scene after the splash screen |
| `adventure/scene/TileMapScene.java` | M +80/-4 | 274 | AdventureQuestController, ColorReputation, Config, Current +18 | 22 | sw2 smut1 | **Medium** | Scene that will render tiled maps. |
| `adventure/scene/DeckSelectScene.java` | U | 246 | AdventurePlayer, Controls, Current, DeckEditScene +3 | 3 | sw1 smut1 lint5 | **Medium** | Deck slot selection scene. |
| `adventure/stage/PointOfInterestMapSprite.java` | M +100/-1 | 155 | ColorReputation, EconomyBuildings, MapSprite, Player +5 | 7 | lint1 | **Medium** | MapSprite for points of interest to add a bounding rect for collision detection |
| `adventure/stage/MapSprite.java` | M +30/-2 | 131 | BiomeSpriteData, Config, MapViewScene, PointOfInterest +4 | 4 | smut2 lint1 | **Medium** | Sprite actor that will render trees and rocks on the over world |
| `adventure/character/OnCollide.java` | M +108/-1 | 130 | MapActor, MapDialog, MapStage, RubbleOverlay +2 | 4 | sw1 | **Medium** | Collision-triggered map action actor. Mod (+108): gated variants (inn always works, etc.). |
| `adventure/scene/GameScene.java` | M +15/-0 | 129 | AdventurePlayer, BiomeData, Config, Current +8 | 15 | smut1 | **Medium** | Game scene main over world scene does render the WorldStage and HUD |
| `adventure/character/QuestActor.java` | M +42/-0 | 88 | AdventureQuestController, DialogActor, MapDialog, MapStage +3 | 2 | - | **Medium** | Quest-giver actor. Mod (+42): Job Board / Capitol-upgrade gating. |
| `adventure/util/pathfinding/NavigationMap.java` | U | 346 | Current, MapStage, NavigationGraph, NavigationVertex +2 | 1 | sw2 | **Low** | Navigation grid built from a tmx collision layer. |
| `adventure/scene/PlayerStatisticScene.java` | M +26/-1 | 340 | AdventurePlayer, Config, Controls, Current +14 | 3 | smut1 | **Low** | Player statistics scene. Mod (+26). |
| `adventure/stage/OrthogonalTiledMapRendererBleeding.java` | U | 283 | - | 0 | - | **Low** | Tiled renderer variant fixing texture bleeding. |
| `adventure/scene/QuestLogScene.java` | M +4/-2 | 244 | AdventureQuestController, AdventureQuestData, AdventureQuestStage, Controls +8 | 6 | smut1 todo1 | **Low** | Quest log scene. Mod (+4). |
| `adventure/character/MapActor.java` | U | 235 | Config, EnemySprite, Forge, PlayerSprite | 8 | - | **Low** | Map Actor base class for Actors on the map implements collision detection. |
| `adventure/scene/HudScene.java` | U | 198 | Forge, GameHUD, GameStage, IAfterMatch +3 | 2 | - | **Low** | Hud base scene |
| `adventure/util/KeyBoardDialog.java` | U | 194 | Controls | 1 | lint1 | **Low** | On-screen keyboard dialog. |
| `adventure/stage/Console.java` | U | 178 | ConsoleCommandInterpreter, Controls, Forge | 3 | lint2 | **Low** | In-game debug console window (history, completion). |
| `adventure/character/PortalActor.java` | U | 173 | Config, EntryActor, MapStage, Paths +1 | 1 | lint1 | **Low** | PortalActor Extension of EntryActor, visible on map, multiple states that change behavior |
| `adventure/util/pathfinding/NavigationGraph.java` | U | 164 | EuclidianHeuristic, NavigationEdge, NavigationVertex, ProgressableGraphPath | 1 | - | **Low** | Indexed graph for gdx-ai A*. |
| `adventure/util/Selector.java` | U | 147 | Controls, KeyBinding | 3 | lint3 | **Low** | UI element to click through options, can be configured in an UiActor |
| `adventure/util/TemplateTmxMapLoader.java` | U | 137 | Forge | 2 | - | **Low** | Rewritten the loadObject method of TmxMapLoader to support templates in tiled map. |
| `adventure/scene/Scene.java` | U | 124 | Config, Forge | 21 | smut1 | **Low** | Base class for all rendered scenes |
| `adventure/scene/InfoTextScene.java` | A +101 | 102 | Controls, EconomyBuildings, EventScene, Forge +5 | 4 | smut1 | **Low** | Mod-new: scrollable long-text page. |
| `adventure/util/KeyBinding.java` | U | 99 | - | 8 | - | **Low** | Enum of key bindings and their preference keys. |
| `adventure/character/PlayerSprite.java` | U | 98 | AdventurePlayer, CharacterSprite, Config, Current +3 | 7 | lint1 | **Low** | Class that will represent the player sprite on the map |
| `adventure/character/EntryActor.java` | U | 82 | MapActor, MapStage, TileMapScene | 2 | - | **Low** | EntryActor Used to teleport the player in and out of the map |
| `adventure/scene/ForgeScene.java` | U | 69 | Forge, Scene | 6 | - | **Low** | base class to render base forge screens like the deck editor and matches |
| `adventure/scene/DeckPreviewScene.java` | U | 67 | AdventureDeckEditor, DeckEditScene, ForgeScene | 1 | smut1 | **Low** | DeckEditScene scene class that contains the Deck editor |
| `adventure/character/RewardSprite.java` | M +9/-1 | 66 | CharacterSprite, EditionProgression, JSONStringLoader, Reward +1 | 2 | - | **Low** | Loot pickup actor. Mod (+9): pickup sound. |
| `adventure/stage/SpriteGroup.java` | U | 63 | - | 2 | - | **Low** | Sprite group to order actors based on the Y position on the map, the render sprites further up first. |
| `adventure/util/pathfinding/MovementBehavior.java` | U | 63 | MapStage | 1 | - | **Low** | Steering behavior wrapper for enemy movement. |
| `adventure/character/DialogActor.java` | U | 60 | AdventureQuestData, CharacterSprite, MapDialog, MapStage | 2 | - | **Low** | Map actor that will show a text message with optional choices |
| `adventure/scene/DeckEditScene.java` | U | 60 | AdventureDeckEditor, AdventureEventData, ForgeScene | 5 | smut1 | **Low** | DeckEditScene scene class that contains the Deck editor |
| `adventure/scene/ViewRewardsScene.java` | U | 60 | ForgeScene, RewardScene | 1 | smut2 | **Low** | Reward-preview Forge scene. |
| `adventure/util/pathfinding/ProgressableGraphPath.java` | U | 58 | - | 4 | - | **Low** | Graph path with progress index. |
| `adventure/util/AdventureModes.java` | U | 57 | Config, Forge | 4 | - | **Low** | Enum of new-game modes (Standard, Constructed, Chaos, Pile, Custom...). |
| `adventure/util/pathfinding/Box2dRaycastCollisionDetector.java` | U | 57 | World | 0 | - | **Low** | Raycast collision detector for steering. |
| `adventure/util/NavArrowActor.java` | U | 51 | Config | 2 | todo2 | **Low** | HUD quest-direction arrow actor. |
| `adventure/util/pathfinding/NavigationVertex.java` | U | 43 | NavigationEdge | 6 | - | **Low** | Graph vertex (tile). |
| `adventure/stage/PointOfInterestMapRenderer.java` | U | 41 | MapStage | 1 | - | **Low** | Custom renderer to render the game stage between the map layers of a tiled map |
| `adventure/character/TextureSprite.java` | M +10/-1 | 35 | MapActor, MapStage | 1 | lint1 | **Low** | Static map sprite. Mod (+10). |
| `adventure/util/pathfinding/NavigationEdge.java` | U | 32 | NavigationVertex | 2 | - | **Low** | Graph edge. |
| `adventure/character/DummySprite.java` | U | 31 | MapActor, MapStage | 1 | - | **Low** | DummySprite Solid map entity. |
| `adventure/util/JSONStringLoader.java` | U | 28 | - | 4 | lint1 | **Low** | JSONStringLoader Wrapper around Json functions for easier loading of arbitrary JSON strings without having to  |
| `adventure/util/SignalList.java` | U | 20 | - | 2 | sw1 | **Low** | List of function points to inform all listeners, maybe redesign to a more java like approach |
| `adventure/data/UIData.java` | U | 16 | - | 2 | - | **Low** | JSON model: a GUI definition, used for most  |
| `adventure/util/pathfinding/EuclidianHeuristic.java` | U | 13 | NavigationVertex | 1 | - | **Low** | A* heuristic. |
| `adventure/stage/IAfterMatch.java` | U | 6 | - | 4 | - | **Low** | Callback interface for scenes that receive a match result. |
#### Pass 5 - Core-engine / launcher / asset edits

| File | Status | Lines | Deps | Used-by | Signals | Risk | Purpose / mod delta |
|---|---|---|---|---|---|---|---|
| `forge-game:game/Game.java` | M +95/-0 | 1,536 | Forge, GameStage, Player, RegisteredPlayer +1 | 19 | smut1 todo1 | **High** | Core rules-engine game state. Mod (+95): ante rarity gate (isUncommonPlus) and reroll memory (recentAnteNames, rerollAnte()). |
| `screens/match/MatchController.java` | M +62/-0 | 882 | AdventurePlayer, Config, DuelScene, EconomyBuildings +6 | 8 | sw2 thr6 smut3 todo3 lint16 | **High** | Match GUI controller. Mod (+62): ante reveal + reroll loop (revealAnteCards). |
| `forge-game:game/player/Player.java` | M +11/-0 | 4,113 | CardUtil, Forge, Game, GameStage +2 | 29 | sw1 ser4 todo6 | **Medium** | Core player. Mod (+11): cards that start on the battlefield tapped, fed from RegisteredPlayer. |
| `Forge.java` | M +4/-1 | 1,703 | AssetsDownloader, Config, DeckSelectScene, DuelScene +17 | 82 | sw21 thr9 smut72 todo1 | **Medium** | libGDX application root: scene stack (switchScene/switchToLast), transition screens, global state. Mod (+4): Android app-dir sniff for the renamed package. |
| `forge-gui-android/src/forge/app/Main.java` | M +7/-7 | 1,071 | AutoUpdater, Forge, ForgePreferences, GitLogs | 14 | sw13 todo1 | **Medium** | Android launcher (AndroidApplication); mod: package rename, asset dir, permissions. |
| `assets/FSkin.java` | M +7/-1 | 678 | FSkinTexture, Forge, ForgeConstants, ForgePreferences +1 | 9 | sw1 thr4 smut4 todo2 | **Medium** | Skin loading; mod: world-gen background. |
| `forge-gui:gui/control/FControlGameEventHandler.java` | M +18/-1 | 554 | ForgeConstants, ForgePreferences, Game, IGuiGame +1 | 0 | thr3 | **Medium** | Game-event to GUI bridge; mod: ante reveal with reroll supplier. |
| `assets/AssetsDownloader.java` | M +44/-6 | 360 | FSkin, Forge, ForgeConstants | 2 | sw3 thr2 | **Medium** | Android asset download / update prompt. Mod (+44): TFR release URLs, build.txt mismatch check. |
| `screens/TransitionScreen.java` | M +62/-11 | 350 | ArenaScene, Config, Controls, Current +9 | 11 | sw1 thr2 | **Medium** | Loading/transition overlay. Mod (+62): enemy stat key, world-gen background. |
| `forge-game:game/zone/Zone.java` | M +10/-1 | 299 | Forge, Game, Player | 4 | ser2 | **Medium** | Core zone. Mod (+10): mutable empty list from the cards-added-this-turn fast path (UnsupportedOperationException crash fix). |
| `forge-gui:localinstance/properties/ForgeProfileProperties.java` | M +43/-8 | 288 | Forge, ForgeConstants, Main | 3 | smut6 | **Medium** | Profile/cache dir resolution. Mod (+43): ForsakenRealms data dirs, reuse of the stock Forge card-picture cache on desktop. |
| `forge-gui:download/AutoUpdater.java` | M +9/-0 | 286 | AssetsDownloader, FSkin, Forge, ForgeConstants +1 | 1 | sw1 thr3 smut1 todo3 | **Medium** | Stock updater, neutralized (if (true) return false;). |
| `forge-game:game/player/RegisteredPlayer.java` | M +12/-0 | 268 | Player | 5 | - | **Medium** | Match setup record. Mod (+12): extraCardsOnBattlefieldTapped / addExtraCardsOnBattlefieldTapped(). |
| `forge-gui:localinstance/properties/ForgePreferences.java` | M +4/-2 | 471 | AbstractPreferences, Forge, ForgeConstants | 17 | smut9 | **Low** | Mod: UI_ANTE, UI_ANTE_MATCH_RARITY prefs. |
| `forge-gui:localinstance/properties/ForgeConstants.java` | M +7/-2 | 464 | Forge, ForgeProfileProperties, Game | 14 | - | **Low** | Mod: project URL, world-gen background file. |
| `toolbox/FCardPanel.java` | M +6/-1 | 344 | FSkin, Forge, MatchController, PlaybackSpeed | 1 | - | **Low** | Card widget; mod: skip animation at FAST/SUPERFAST. |
| `forge-gui:gui/interfaces/IGuiGame.java` | M +18/-0 | 334 | Forge, MatchController, PlaybackSpeed | 3 | ser1 | **Low** | GUI interface; mod: revealAnteCards default method. |
| `assets/FSkinTexture.java` | M +3/-0 | 284 | FSkin, Forge, ForgeConstants, TransitionScreen +1 | 4 | smut1 | **Low** | Skin texture enum; mod: ADV_WORLDGEN_BG. |
| `forge-gui-desktop/src/main/java/forge/menus/HelpMenu.java` | M +3/-1 | 200 | Forge, ForgeConstants | 0 | - | **Low** | Swing help menu; mod: project URL. |
| `forge-gui-mobile-dev/src/forge/app/GameLauncher.java` | M +20/-1 | 172 | Config, Forge, Main, Paths | 0 | sw1 | **Low** | Desktop LWJGL3 launcher; mod (+20): window title and icon set. |
| `forge-gui:localinstance/properties/AbstractPreferences.java` | M +8/-0 | 170 | Forge | 1 | - | **Low** | Mod: create parent dir before saving prefs. |
| `forge-gui-android/src/forge/app/GitLogs.java` | M +4/-1 | 77 | - | 1 | sw3 | **Low** | Android crash-log helper; mod: package/branding. |
| `forge-gui:gui/control/PlaybackSpeed.java` | M +10/-1 | 49 | Forge | 2 | - | **Low** | Mod: SUPERFAST speed. |

