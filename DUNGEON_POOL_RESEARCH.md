# Dungeon Pool Research — Cross-Plane Filler Content Audit (2026-08-10)

Research-only pass, no code/data changed. Answers one question: **which non-quest, non-city,
randomly-spawning dungeon/cave content from the other bundled Adventure planes could be pulled
into "The Forsaken Realms"'s dungeon rotation pool for extra variety?** Written for whichever
session (this PC or the Gaming PC) picks up the actual import work — every claim below was
verified directly against the JSON/asset files, not taken on faith from a first-pass search.

**Ties to:** `MOD_SCOPE.md` #15 (Dungeon Rotation, built 2026-08-08) — that system already *is*
"the mechanic that brings in new dungeons over time" the user referenced when asking for this.
The wishlist's older "Dungeons/caves spawning and despawning over time... Not Started" note
(under the "More raised by the user 2026-08-05" section, further down in the same file) is
**stale** — #15 shipped it 3 days later. Not fixing that stale note in this pass since it's just
a leftover cross-reference, not incorrect status tracking on #15 itself.

## How rotation eligibility actually works (read this before adding anything)

`DungeonRotation.java`'s whitelist (confirmed by reading the source, `forge-gui-mobile/src/forge/
adventure/util/DungeonRotation.java` ~line 65-82):
- `type` must be `dungeon` or `cave`.
- Must carry the **`"Hostile"` questTag literally** — no Hostile tag = never rotates (world-gen
  places it once, normal static behavior, but it won't vanish/reappear or draw from the reserve
  pool).
- Excluded outright: `name` starting with `Quest_`, `name` equal to `DEBUGZONE`/`Test`, or any
  questTag equal to `Story` or starting with `Quest_`.

So "add it to the rotation pool" isn't automatic — every candidate below needs the `Hostile` tag
checked/added, on top of just existing as a POI entry. Several candidates found here are missing
it; noted per-entry.

**Placement also requires biome wiring.** A POI entry existing in `points_of_interest.json` is
not enough for world-gen to ever place it — each of the plane's 6 `world/biomes/*.json` files
(`black`/`blue`/`green`/`red`/`white`/`colorless.json`) carries its own array of POI names to
place in that biome. Biome-tagged entries (`BiomeGreen` etc.) belong in the matching color file
only (confirmed: `Grove7` only appears in `green.json`); untagged/generic ones get listed in
several files (confirmed: `Aerie` appears in `blue.json`, `colorless.json`, and `white.json`).

## Baseline

- `common/world/points_of_interest.json`: 264 POI objects total, 208 of them `cave`/`dungeon`
  (105 cave + 103 dungeon), all unique `name`s.
- `The Forsaken Realms/world/points_of_interest.json` is **no longer a byte-identical copy of
  common's** — it picked up 16 extra entries during the 2026-08-10 "Item Economy overhaul" (a
  quest-item-sourcing import from Realm of Legends, see `MOD_CHANGELOG.md`'s entry of that name).
  Current FR file: 217 `cave`/`dungeon`-type entries. **Every name check in this doc was run
  against FR's actual current file, not just common's**, after an earlier pass (see Methodology
  note below) wrongly treated common as the sole baseline and produced 4 false positives.
- Planes with **no custom `points_of_interest.json` at all** (inherit common's roster wholesale,
  contribute zero new dungeon content): `Amonkhet`, `Shandalar` (the base plane, distinct from
  "Shandalar Old Border").

## Bonus finding: 19 dungeons already defined in FR but never placed anywhere (zero import needed)

Before looking at other planes at all — FR's own `points_of_interest.json` already defines 19
`cave`/`dungeon` entries that **no biome file references**, so world-gen never places them. This
is free variety: no asset copying, no cross-plane audit risk, just wiring. Verified via a
PowerShell scan cross-referencing all 217 cave/dungeon names against the text of all 6 biome
files.

| name | type | map (already exists) | notes |
|---|---|---|---|
| CaveBA, CaveCD, CaveGB, CaveRJ | cave | `../common/maps/map/cave/cave_huge.tmx` | 4 near-identical "huge cave" instances, tagged Cave/Hostile — plain generic filler, cheapest possible win |
| GroveFaerieDragon | cave | `../common/maps/map/grove/grove_12_faeriedragon.tmx` | tagged BiomeGreen/Grove — belongs in `green.json` only |
| Lich's Mirror | dungeon | `../common/maps/map/magetower/magetower_12_lichsmirror.tmx` | tagged Hostile/DungeonEffect/Dungeon — generic filler |
| MageTowerC, C2, C3, C4, C5, C6, C8, CE | dungeon | `../common/maps/map/magetower/magetower_*.tmx` | 8 separate mage-tower flavors, mostly tagged BiomeColorless/MageTower/Dungeon — a whole unused sub-family |
| Maze3 | dungeon | `../common/maps/map/maze/maze_4.tmx` | tagged Hostile/Maze |
| Oasis | cave | `../common/maps/map/oasis.tmx` | `count: 10` in its own definition — was clearly meant to be common, just never wired in |
| **Valor's Reach Arena** | cave | `../The Forsaken Realms/maps/map/cave/Valors_Reach_Arena.tmx` | **plane-local, mod-specific asset** — art/map was already custom-built for this mod and is sitting completely unused |
| Fort | dungeon | `../common/maps/map/fort/fort_colorless_1_snow.tmx` | grep output around this entry looked slightly malformed (possible duplicate `"type"` key spillover from the adjacent object) — **read this one entry directly before using it**, didn't get a fully clean parse |
| DEBUGZONE | — | — | correctly excluded — this is dev/test content, leave it out |

**Recommendation: do this pass first.** 17 of these 19 (excluding DEBUGZONE and pending the
`Fort` sanity check) can go live by (1) adding the `Hostile` tag where missing and (2) adding the
name to the appropriate biome file(s) — no `.tmx`/atlas copying, no cross-plane risk, and
`Valor's Reach Arena` in particular is finished mod-specific content nobody ever turned on.

## Cross-plane survey results, by plane

### Crystal_Kingdoms — 0 usable

225 `cave`/`dungeon` entries total. 208 are exact re-listings of the common baseline (same
`name`s). The other 17 all resolve to **names that already exist in common/FR** — the previous
pass's "17 new" claim was wrong; direct verification (`grep -c` against both files) confirmed
`Grove7`, `Slime Cave`, `UnhallowedAbbey`, `VampireCastle3` are already present, and the remaining
13 are the same disguised Boss/Planeswalker encounters listed below under Old Border (Crystal
Kingdoms just re-flavors the identical roster with different `displayName`s). **Net contribution:
zero new content, of any kind.**

### Shandalar Old Border — 7 usable candidates, all require asset copying

237 `cave`/`dungeon` entries. 203 re-list the common baseline. Of the remaining 34, direct
verification narrows it to:

**Confirmed genuinely absent from both common and FR, and confirmed generic (no Boss/Story/
Planeswalker/ElderDragon tag):**

| name | displayName | type | map | spriteAtlas | questTags |
|---|---|---|---|---|---|
| DemonsBargain | Demon's Bargain | cave | `../Shandalar Old Border/maps/map/lair/demons_bargain.tmx` | `sprites/buildings.atlas` | *(empty)* — **no Hostile tag, add one** |
| AncientDiamondMine | Ancient Diamond Mine | cave | `.../lair/ancient_diamond_mine.tmx` | `sprites/buildings.atlas` | DiamondMine, Hostile |
| RiddlesLair | Sphinx's Sanctum | cave | `.../lair/riddles_lair.tmx` | `sprites/buildings.atlas` | *(empty)* — **no Hostile tag, add one** |
| DragonsLairWhite | Dragon's Lair | cave | `.../lair/dragons_lair_white.tmx` | `sprites/buildings.atlas` | *(empty)* — **no Hostile tag, add one** |
| DragonsLairBlue | Dragon's Lair | cave | `.../lair/dragons_lair_blue.tmx` | `sprites/buildings.atlas` | *(empty)* — same |
| DragonsLairBlack | Dragon's Lair | cave | `.../lair/dragons_lair_black.tmx` | `sprites/buildings.atlas` | *(empty)* — same |
| DragonsLairRed | Dragon's Lair | cave | `.../lair/dragons_lair_red.tmx` | `sprites/buildings.atlas` | *(empty)* — same |
| DragonsLairGreen | Dragon's Lair | cave | `.../lair/dragons_lair_green.tmx` | `sprites/buildings.atlas` | *(empty)* — same |

All 7 verified present on disk. **Caveat, not yet checked:** the 5 empty-tag `Dragon's Lair`
entries and `RiddlesLair`/`DemonsBargain` were only audited at the JSON-metadata level (name,
type, tags) — nobody has opened the actual `.tmx` files to confirm what enemy/loot object sits
inside. The generic, unnamed "Dragon's Lair" naming (color-flavored, no `ElderDragon`/`Boss` tag,
unlike the 5 confirmed Elder Dragon encounters below) reads as the classic mid-tier repeatable
dragon dungeon type, distinct from the unique legendary Elder Dragons — plausible, not confirmed.
**Open the map files before committing to "these are plain filler."**

**Asset copying required for all 7** — this plane keeps its own customized `maps/`/`sprites/`
folders (spot-checked: its `garruk.tmx` and `buildings.atlas` are genuinely different byte
content from common's, not pointers), so nothing here is a free common-only reuse. Per this
project's standing "prefer plane-local storage" convention (see `CLAUDE.md`), land copied assets
under `The Forsaken Realms/maps/...`, not `common/`.

**Explicitly excluded, and why (don't re-import these):**
- **13 disguised Boss/Planeswalker encounters** (Garruk Forest, Grolnoks Bog, Jacehold, Kiora
  Island, Nahiri Encampment, Thallid Grove, Scarecrow Farm, Slobads Factory, Teferi Hideout,
  Tibalts Fortress, Xiras Hive, Skep, Zedruu City) — `type: dungeon`/`cave` but tagged
  `Boss`/`Planeswalker`, backed by dedicated unique encounter maps. Not filler by any reading of
  "no quests associated" — these are hand-placed unique fights.
- **5 Elder Dragon lairs** (CaveArcades/Chromium/NicolBolas/PalladiaMors/Vaevictis) — tagged
  `ElderDragon`+`Boss`, and confirmed backed by dedicated legendary decks under
  `Shandalar Old Border/decks/miniboss/` (`chromium.dck`, `nicol bolas.dck`, etc.). Real unique
  bosses, not filler. (Also: your own 2026-08-10 "Boss drops" changelog round already explicitly
  considered and declined importing Old Border's bestiary for a different task — consistent with
  this finding.)
- **ArtificerBazaar / ArtificerBazaar2 / ArtificerBazaar3** — `type: dungeon` in the POI file, but
  cross-checked against `world/shops.json`: these are actually vendor/shop locations
  (`restockPrice`, `rewards` list), not combat encounters. Mislabeled `type` in the source data —
  exclude from dungeon consideration entirely.
- **MageTowerG** ("Druid's Greenhouse") — its map (`magetower_5_greenhouse.tmx`) is the exact same
  file the baseline's `MageTowerC5` already uses. A rename, not new content.

### Realm of Legends — 0 usable, structurally

93 `cave`/`dungeon` entries, **100% of them tagged `"Story"` and nothing else**, each `count: 1`
(single fixed placement, not a repeatable template) — e.g. `Teferi's Hideout`, `Sheoldred's
Stronghold`, `Karn's Factory`, all five guild `Sphinx of Riddles` variants, all ten Arena/
Strixhaven classroom locations, faction `War Camp` HQs. This isn't a borderline case: `Story` tag
is a hard exclusion in `DungeonRotation`'s own eligibility check, so even setting aside the "no
quests associated" ask, none of these could ever rotate as currently coded. This matches (and is
presumably *why*) the 2026-08-10 Item Economy round only imported 16 *specific* Realm of Legends
locations for their unique quest-item drops, not as generic filler — that import and this ask are
different use cases pulling from the same source plane; don't conflate them.

### Innistrad — 4 usable candidates, clean and small

Only 11 POI entries total in this plane; 4 are `cave`/`dungeon`, and all 4 are genuinely new,
generic, already `Hostile`-tagged:

| name | type | count | questTags | map | spriteAtlas |
|---|---|---|---|---|---|
| `inn_Cave_river` | cave | 12 | Hostile, Cave, BiomeColorless, Sidequest | `../Innistrad/maps/map/Innistrad/cave/inn_cave_river_entrance.tmx` | `../Innistrad/maps/tileset/inn_buildings.atlas` |
| `inn_dark_forest` | cave | 10 | Hostile, Cave, BiomeColorless, Sidequest | `.../Innistrad/inn_approaches/dark_forest.tmx` | same atlas |
| `inn_forgotten_lodge_1` | dungeon | 18 | Hostile, Dungeon, BiomeColorless, Sidequest | `.../Innistrad/hunting_lodge/inn_forgotten_lodge_1.tmx` | same atlas |
| `inn_lodge_1` | dungeon | 12 | Hostile, Dungeon, BiomeColorless, Sidequest | `.../Innistrad/hunting_lodge/inn_lodge_1.tmx` | same atlas |

All verified present on disk (map files, `inn_buildings.atlas`, and its full local tileset
folder). Two things to fix on import:
- **None of the 4 have a `displayName` field** (only the internal `name`). Confirmed safe
  (`PointOfInterestData.getDisplayName()` falls back to raw `name` if empty — verified in
  `forge-gui-mobile/src/forge/adventure/data/PointOfInterestData.java:73-78`), so this won't
  crash, it'll just show the player something like literal `"inn_Cave_river"` in-game. Give these
  real display names when porting (e.g. "River Cave", "Dark Forest", "Forgotten Hunting Lodge",
  "Hunting Lodge").
- `count` values (10-18) are unusually high — baseline `count`s for single templates are mostly 1
  (178 of 208 baseline entries), with a handful going up to 100. Not a bug (Innistrad's own
  world-gen presumably tunes for its own smaller map), but worth deliberately choosing a `count`
  for this plane rather than copying Innistrad's number verbatim.
- Asset copying required: `Innistrad/maps/tileset/inn_buildings.atlas` (+ backing `.png`) and the
  4 `.tmx` files (+ whatever tilesets those `.tmx`s themselves reference — not yet individually
  traced, do that as part of the import, same process the 2026-08-10 Realm of Legends import used:
  verify every `<tileset source>` line only points at assets that will actually exist post-copy).

## Net honest total

You expected "a lot." The real number, after filtering out disguised boss/story content (which
turned out to be the overwhelming majority of everything that looked new), is:

- **17 free** (zero asset cost, just tagging + biome wiring) from FR's own already-defined-but-
  unplaced entries.
- **11 more** requiring asset copying: 7 from Shandalar Old Border, 4 from Innistrad.
- **0** from Crystal_Kingdoms (pure re-listing) and Realm of Legends (100% Story-tagged, doesn't
  fit this mechanic at all).

**28 total candidates** against a current pool of 217 — roughly a 13% variety increase, weighted
toward the free 17. Most of what superficially looked like "lots of new dungeons" in the other
planes turned out to be the same handful of named Planeswalker/Elder Dragon bosses reskinned
across 3 planes (Crystal Kingdoms, Shandalar Old Border, and common itself) — which is exactly
the roaming-boss content your 2026-08-10 session (`387ff3b26e1`, "Surface the 38 orphaned
Shandalar Old Border bosses...") already went and surfaced separately, on purpose, as unique
encounters rather than filler.

## Implementation checklist (for whenever this gets built)

1. **Do the free 17 first** (minus DEBUGZONE, plus a manual look at `Fort`). Add `Hostile` tag
   where missing, wire each name into the right biome file(s) per its `BiomeX` tag (or several
   files / `colorless.json` if untagged).
2. For the 11 asset-requiring imports: copy `.tmx` + atlas + backing `.png` files into `The
   Forgotten Realms/maps/...` (not `common/`, per project convention), fixing relative paths as
   you go (`../Shandalar Old Border/...` / `../Innistrad/...` → `../The Forsaken Realms/...`).
3. **Check every copied `.tmx` for internal door/teleport properties pointing at the source
   plane** before wiring it in — this exact bug bit the 2026-08-10 Realm of Legends import ("Six
   broken cross-plane dungeon exits", walking into an unconnected door tried to load a file that
   doesn't exist on disk and would have crashed). None of the 11 candidates here are known to have
   internal sub-level doors (unlike the multi-room Eldrazi Prison case), but verify per-file
   before trusting that.
4. Add `questTags: ["Hostile", ...]` to any candidate missing it (5 Dragon's Lairs, RiddlesLair,
   DemonsBargain) — otherwise they'll place once at world-gen and sit there forever instead of
   joining the rotation pool.
5. Open the 7 Old Border `.tmx` files with empty/thin tags to sanity-check actual encounter
   difficulty before treating them as ordinary filler (see caveat above).
6. Remember `DungeonRotation`'s `POOL_MULTIPLIER` (5x) means every new rotating entry gets placed
   5 times at world-gen — a known lever if a fresh world ever hangs at POI placement (documented
   in `MOD_CHANGELOG.md`'s "Playtest fix round + pool rotation" entry).
7. New POI entries + biome file edits are exactly the kind of change `CORE_ENGINE_CHANGES.md`
   does **not** need to track (everything here lives under `The Forsaken Realms/`, not a shared
   engine file) — but log the actual import in `MOD_CHANGELOG.md` and update `MOD_SCOPE.md` #15's
   status line when it happens.

## Methodology note (why some numbers here differ from an earlier internal pass)

Two research agents did the first pass and reported Crystal_Kingdoms had "17 new" entries and
flagged 4 of them as plausible generic filler (`Grove7`, `Slime Cave`, `UnhallowedAbbey`,
`VampireCastle3`). That "not in baseline" comparison was run against `common`'s list at a point
before it was confirmed that **FR's own file has diverged from common's** (16 extra entries from
the 2026-08-10 Realm of Legends quest-item import) — direct `grep -c` verification against both
files afterward showed all 4 were false positives, already present in common all along. Every
name in this document's tables was re-verified directly against both `common/world/
points_of_interest.json` and `The Forsaken Realms/world/points_of_interest.json` before being
listed — this doc's numbers supersede the first pass.
