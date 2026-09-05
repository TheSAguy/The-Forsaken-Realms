# MTG Forge Mod — Project Scope / Wish List

Living list of ideas for the mod. Not prioritized, not all guaranteed to happen. Ask Claude
to "show the mod scope" or similar at any time for a status recap. Edit freely as things
change — add ideas, cross things off, revise scope.

**Status legend:** `Not Started` · `In Progress` · `Done` · `Open Question` (design not settled yet)

**Currency:** caught up to **round 79 (2026-09-01)**. This file went stale between rounds 61 and 77
while `MOD_CHANGELOG.md` kept running; items **#92-#100** were backfilled in one pass on 2026-09-01
to close that gap. A user status pass the same day closed #12/#25/#31/#42/#54/#81/#90, moved #11 to
In Progress, removed #86, and added named targets to #84 and #87. Per-round engineering detail always lives in `MOD_CHANGELOG.md`
and every engine-file edit in `CORE_ENGINE_CHANGES.md` - this file is the feature list and its
status, nothing more. **Nothing since the v1.03 release (2026-08-27) has shipped to players**:
rounds 62-77 are all local-only, awaiting a test pass.

## Theme

Make the Shandalar-style overworld a lot more dynamic and interactive — the five colors
struggle against each other, the player has a reputation with each of them, and the world
visibly changes over time instead of sitting static.

## Mod Plane: "The Forsaken Realms"

All of this is being built as its own selectable plane, `forge-gui/res/adventure/The Forsaken
Realms/` (currently a copy of Shandalar's `world/` data as a starting point). The plane has its
own `config.json` (a full copy of `common/config.json`, since per-plane configs replace the
common one entirely rather than merging with it) with our feature flags turned on:
`fogOfWarEnabled`, `dayNightCycleEnabled`, `townReconstructionEnabled` — all `false` by default
in the Java code, so every mod feature is opt-in per plane and **does not affect Shandalar or
any other stock plane** unless that plane's own config.json also sets them. Select "The
Forsaken Realms" in-game (New Game screen) to play/test the mod.

## Color Alliances / Enemies

Core rule that several systems below depend on (reputation, territory attacks): standard
MTG color pie adjacency.

| Color | Allies | Enemies |
|-------|--------------|--------------|
| White | Green, Blue | Black, Red |
| Blue | White, Black | Red, Green |
| Black | Blue, Red | Green, White |
| Red | Black, Green | White, Blue |
| Green | Red, White | Blue, Black |

Helping a color angers its two enemies, not its allies.

## Features

### 1. Reputation System — `Done (playtest-confirmed 2026-08-13)` (scoring + consequences built 2026-08-07)
- Player has a reputation score per color (5 tracks).
- Helping/hurting one color affects reputation with it, and ripples to its allies/enemies
  per the table above (help Green → Blue & Black annoyed).
- ~~Rep ≥ 100 / ≤ -100 thresholds~~ superseded by the user's 5-tier table below (2026-08-07).
- **Consequences built (same day as scoring, per the user's spreadsheet - thresholds are display
  values; tier labels settled after two rounds of user correction, final answer: Unhappy is the
  MODERATE negative tier, War the SEVERE one):**
  | Status | Scale | Effects |
  |--------|-------|---------|
  | Partner | ≥ 80 | 30% cheaper card shops in that color's towns; player-owned towns 75% less likely to be mage-targeted by that color (weight x0.25, round 112 tuning) |
  | Happy | 30 to 79 | 15% cheaper; 50% less likely (x0.50) |
  | Neutral | -29 to 29 | none |
  | Unhappy | -30 to -79 | 25% pricier; 15% more likely (x1.15) |
  | War | ≤ -80 | barred from that color's towns (capitals charge a 500-gold entry toll instead, prices 40% up inside); 50% more likely (x1.50) |
  - **Console command for testing**: `give rep <color> <amount>` (negative allowed) shifts one
    color by a display amount, spreading the negation across the other 4 so net-zero holds -
    added because reaching ±80 legitimately takes ~40 duel wins. (For force-winning actual
    duels: no adventure-console command exists - Forge's own Developer Mode in game settings has
    in-duel dev tools, e.g. set AI life to 0.)
  - **"Less/more likely to be attacked" = mage TARGETING odds** (user clarification): when a
    color picks among its 5 nearest capturable towns (measured from any of its owned properties
    since the 2026-08-08 targeting change - see #7), a PLAYER-OWNED town's selection weight
    scales by the tier - this is the reputation gate the Territory Control targeting design
    explicitly deferred ("eventually meant to be gated by a reputation scale once #1 exists").
    Non-player towns keep uniform odds.
  - **Player-owned towns are exempt from ALL color effects** ("the player's towns should not
    match any color") - no price change, no entry bar, regardless of the town's color name.
  - **Prices: card shops only** for now (the existing `ShopActor.getPriceModifier()` hook -
    stacks multiplicatively with the pre-existing per-town haggling rep). Inn/spellsmith/trader
    pricing deliberately untouched.
  - **Entry bar**: ordinary towns of the severe-tier color bounce the player with a notification;
    CAPITALS offer a pay-500-gold-to-enter dialog instead (user request - story bosses live in
    capitals, a hard bar risks soft-locks; toll raised 100 -> 500 in the same tier-tweak round
    that finalized the table above). Quest targets inside barred ordinary towns stay barred
    (accepted; raise rep or abandon).
  - Not yet built: "that color stops attacking the player" as a Partner perk in the
    roaming-monster sense - currently the attack-odds effect only covers mage town-targeting,
    per the user's clarified definition. Revisit if wanted later.
- **Scoring slice built (2026-08-07, home PC)** — the tracking/scoring half, per detailed user
  design; consequences (the three bullets above) deliberately later, "let's first get the
  scoring working":
  - **Net-zero invariant**: the 5 values always sum to exactly 0 - every event is a zero-sum
    redistribution across the wheel, never a plain gain/loss.
  - **Winning an ordinary duel** vs a mono-color enemy: that color -2, its 2 allies -1 each, its
    2 enemies +2 each. Multicolor enemy: HALF that pattern, applied once per enemy color (user's
    pick among offered options; a large share of enemies are multicolor - 220ish mono vs 150ish
    multi in common's enemies.json). Bosses (EnemyData.boss) count 3x. Losses, colorless
    enemies, Arena brackets, and Inn tournaments: no effect. Every colored enemy counts (wolves
    included, not just mages - user's pick).
  - **Internal half-point storage**: halving -1 with rounding would break net-zero every
    multicolor fight, so values are stored doubled (`AdventurePlayer.colorReputationHalfPoints`)
    - every case stays an exact integer internally; only the display divides by 2
    (`ColorReputation.displayValue()`, rare leftover halves - multicolor boss only - round for
    display while storage stays exact).
  - **Starting deck seeds reputation** (+10 per deck-identity color, +5 its allies, -10 its
    enemies - applied per color for multicolor starters, nothing for colorless; user's pick).
    Hook in `AdventurePlayer.create()` only - identity changes later in a run don't re-seed.
  - **World Standings page**: new Reputation column after Town Count with real column headers;
    blank for the Player and Colorless rows. Rows stay in town-count order (headers are labels,
    not sort toggles - user okay'd deferring sortability). **Number color re-tuned (2026-08-11)**
    from plain sign-based (green positive/red negative/plain 0) to tier-based per user spec: Red
    War, Orange Unhappy, Green Partner, light blue (Cyan) Happy, Neutral stays plain.
  - **Uncapped for now** (user's pick) - capping breaks net-zero, so caps need their own design
    when consequences arrive.
  - Opt-in via `colorReputationEnabled` (ConfigData + the plane's config.json), independent of
    `territoryControlEnabled` - reputation works even with territory control off.
  - Files: `ColorReputation.java` (new - wheel, rules, half-point math), `AdventurePlayer.java`
    (storage/save/load/create-hook), `DuelScene.java` (win hook in `afterGameEnd()`, the single
    funnel where win/Arena/event status are all knowable), `WorldStandingsScene.java` +
    `world_standings.json` (UI), `ConfigData.java` + plane `config.json` (flag). Engine-file
    edits recorded in `CORE_ENGINE_CHANGES.md`.
- **Round of tweaks (2026-08-10, not yet playtested):**
  - **Tier ranges adjusted** per user spec: Neutral widened to -29..29 (was -19..19), Happy is now
    30..79 (was 20..79), Unhappy is now -30..-79 (was -20..-79). Partner (≥80) and War (≤-80)
    thresholds unchanged.
  - **Killing a Territory Control attack mage (#7) is worth 2x** the normal duel-win reputation
    swing, on top of stopping whatever town it was going for - detected via
    `EnemySprite.territoryColor != null` at the same `DuelScene.afterGameEnd()` funnel every other
    reputation event already goes through. Mutually exclusive with the existing boss 3x in
    practice (mages aren't tagged boss).
  - **Inn healing now reputation-gated**: War-tier towns bar the paid heal option outright
    (`ColorReputation.isHealBarred()`); Partner-tier towns grey it out for the opposite reason -
    see the free overheal below, a purchase would be redundant. Happy/Neutral/Unhappy unchanged
    (Unhappy explicitly still allowed - an earlier draft of this idea had Unhappy barred too,
    dropped by the user before building).
  - **Partner-tier free overheal**: entering any Partner-tier color's town or Capitol auto-grants
    life = maxLife+2 (`AdventurePlayer.grantPartnerOverheal()`), simplified per the user to trigger
    on town ENTRY, not specifically visiting the Inn. "Lose it if you don't use it": cleared back
    to maxLife on the next duel (`DuelScene.GameEnd()`, same funnel `clearBlessing()` already
    uses) or on entering any non-Partner town/capital first, whichever comes first
    (`TileMapScene.enter()`). Deliberately a separate flag/mechanism from the pre-existing paid
    `potionOfFalseLife()` (no flag, untouched by any of this) rather than reusing it.
  - **Real bug, playtest-caught and fixed (2026-08-11): the free heal itself was never actually
    gated by reputation.** `TileMapScene.enter()`'s pre-existing, unconditional `fullHeal()` call
    (predates the reputation system) kept firing on every town/capital entry regardless of tier -
    the Bless logic above only ever controlled the +2 BONUS on top of it, not the base heal, so
    Unhappy/War-tier towns still fully restored the player's life (user report: "still getting
    life restored... unhappy/at war with"). New `ColorReputation.isFreeHealBlocked()` (Unhappy or
    War, distinct from the Inn-specific `isHealBarred()` above which stays War-only) now also
    gates the base heal.

### 2. Central Wasteland & Town Reconstruction — `Done (playtest-confirmed 2026-08-13)`
- First slice built: towns in the colorless "Wastes" biome (existing stand-in for "the middle
  of the map" until full territory control exists, #7) now start destroyed. The Job Board
  (quest giver) must be restored for 100 gold before any of that town's shops can be
  individually rebuilt, also 100 gold each. State is per-town/per-shop, persisted via the
  existing map-flag save system (`TownRestoration.java`).
- **Real art added for the neutral/artifact broken town's overworld icon** (previously a
  procedural placeholder tint): 16 hand-made ruined-castle variants, one randomly (but stably)
  assigned per town. Swaps back to the normal town icon once that town's Job Board is
  restored. Per-color variants (5 more sets, one per WUBRG) are planned next.
- **Real art added for destroyed shops** (2026-08-04, previously the procedural `RubbleOverlay`
  tint): 64 hand-made ruined-shop variants, one picked stably per shop. Source art is 32x32,
  drawn scaled down to a shop's native 16x16 footprint. Swaps back to normal once that specific
  shop is rebuilt. **The Job Board itself still uses the procedural rubble overlay** - only
  shops have real art so far, no Job Board-specific art exists yet.
- **Shop ruin variant bug fixed (2026-08-11, round 7)** - user report: "the ruin images being used
  for the towns/capitol... currently hard-coded to be the same set each time." Real bug, not a
  perception issue: `getBrokenShopSprite(objectId)` picked its variant purely from the shop's Tiled
  object id, with no per-town salt - since every town on the map is built from one of a small
  handful of shared `.tmx` templates, a given "shop slot" has the IDENTICAL object id in every town
  sharing that template, so every one of them showed the exact same ruin variant. (The town/
  capitol-level overworld icon, `getBrokenTownSprite()`, was already correctly varying per town -
  it salts off the POI's own id, which incorporates real world position - only the per-shop pick
  had this gap.) Fixed by combining in the current town's own POI id as a salt.
- Still to do: gradual leveling as a town is rebuilt (more shops unlocked per level), roads
  built between towns, +1 life at max reconstruction level.

### 3. Fog of War — `Done (playtest-confirmed 2026-08-18)`
**The 3 FoW stages (reference numbering, so we can talk about these by number - `World.java`):**
| Stage | Name | Look | How a tile gets there |
|---|---|---|---|
| 1 | Unexplored | Solid black | Default - player has never been near it |
| 2 | Known | Hazed/dimmed (55% black veil over the real terrain) | Once explored (`explored[][]`), stays this way whenever the tile isn't currently Stage 3 |
| 3 | Revealed | Full brightness | Live vision radius around the player right now, **or** "persistently revealed" (`isPersistentlyRevealed()`, player-position-independent): ground actually painted with the player's biome bit (captures/expansion), or inside any player-owned town's vision circle - fixed `CASTLE_KEEP_RADIUS_TILES` for the Capitol, `townTerritoryRadius` (grows daily) for any other restored town, doubled/tripled by an Outlook building |

The "discovery flash" (a newly-found POI briefly clears to full brightness before settling into its
normal tier) is a time-limited variant of Stage 3, not a 4th stage - `isTemporarilyRevealed()` is
just another OR branch alongside live-vision and persistently-revealed inside `isCurrentlyVisible()`.

- **Capitol upgrade missing its Stage-3 reveal, fixed (2026-08-13)** - user report + screenshots:
  terrain around an established Capitol was still hazed (Stage 2) despite being player-owned/in the
  Capitol's vision circle. Root cause: every OTHER event that changes a town's vision-circle size
  (Outlook build/destroy, a regular town's daily territory growth) pairs `rebuildPlayerTownVision()`
  (updates the in-memory circle) with a one-time `revealArea()` + `refreshFogInRadius()` (actually
  re-bakes the affected tiles/minimap - see `EconomyBuildings.onOutlookChanged()`'s own doc comment
  for this exact "cache updated, nothing repainted" bug class) - `TownRestoration.upgradeToCapitol()`
  rebuilt the cache but never did the second half, so the ring between the old town's smaller
  pre-upgrade repaint radius and the Capitol's larger fixed keep radius stayed hazed forever unless
  an Outlook happened to be built later (which does its own correctly-bounded reveal as a side
  effect). New shared `applyCapitolVisionReveal()` helper, called both from `upgradeToCapitol()`
  (new upgrades) and `repairCapitolState()` (runs every load - self-heals existing saves upgraded
  before this fix, safe to repeat since `revealArea()`/`refreshFogInRadius()` are idempotent).
  Not yet playtested - needs a save with an existing Capitol reloaded, or a fresh upgrade.
- Already underway (`forge-gui-mobile/src/forge/adventure/...`, opt-in via
  `config.json` → `fogOfWarEnabled`). Makes exploring the world feel scarier/less known.
- Tuned down for testing: vision radius halved (6 → 3 tiles), discovery-reveal radius reduced
  to 75% (15 → 11 tiles) - both meant to be raised later via items/upgrades.
- Moved from a live in-game HUD toggle to a real Settings-screen checkbox
  (`SettingData.fogOfWarEnabled`, persisted, defaults **off**). The in-game toggle was removed -
  flipping it live mid-session didn't cleanly reset the Known/Visible rendering state, so it's
  now a setting you pick before/between sessions instead. Still requires the plane's own
  `config.json` opt-in on top of this (both need to be on).
- **Two-tier now, not just on/off:** "known" (terrain you've been near once - persisted, shown
  hazed/dimmed when you're not currently there) vs "currently visible" (live vision radius
  around the player right now - full brightness, and the only state monsters render in). You
  remember the shape of the land once known, but not what's moving around on it. See
  `MOD_CHANGELOG.md` for the implementation.
- **Discovery flash added (2026-08-10):** the wider reveal burst around a newly-discovered town/
  capital used to jump straight to the dimmed "known" tier the instant it was uncovered - user
  spec: "when you first get close to a town, or enemy capitol... the FoW should clear briefly,
  then go to the middle state." Newly-explored tiles from that burst now render at full brightness
  for a few seconds before settling into the normal dimmed tier, via a new time-limited "temporary
  reveal" layer in `World.java` (separate from the persistent known/visible tiers - purely
  cosmetic, doesn't affect what's actually explored). Only fires for genuinely new discoveries -
  re-approaching an already-known town doesn't re-flash.
- **Four playtest fixes to the discovery-burst mechanic, same code path, all 2026-08-11:**
  reserved (inactive) Dungeon Rotation reserve-pool slots were lifting fog exactly like a real,
  currently-active dungeon (now skipped via `getActive()`); the reveal radius is now two-tiered
  (town/capital/castle keeps the original 11, dungeon/cave/sideboss drops to ~50%, 6, per user
  spec); the proximity check is now measured from a POI's bounding-rectangle CENTER instead of its
  raw top-left position (a large town/capital sprite's corner could sit many tiles from where the
  player can actually stand, which was the real reason towns rarely lifted fog while small dungeon
  icons reliably did); and a real, unrelated bug where the Capitol's daily Territory Expansion
  growth (up to 450 tiles, matches an AI castle's own cap) was force-marking its entire growing
  disc as permanently "explored" regardless of whether the player had ever been there - by day 33
  this had force-revealed nearly the whole reachable map as a giant, unintended Stage-2 circle. The
  redundant `revealArea()` call causing it (in `TerritoryControl.java`'s Capitol-expansion block,
  not present in the 5 AI castles' otherwise-identical loop) was removed; territory
  ownership/color-painting growth is unaffected, only the forced fog reveal stops. Full root-cause
  detail for all four in `MOD_CHANGELOG.md`.
- **Second pass, same day (playtest round 2): the "towns don't lift fog" fix above was still
  inconsistent** - "have to approach the town from just the exact angle" (user's own diagnosis,
  confirmed correct). Gating on distance to a POI's rectangle CENTER meant the effective trigger
  radius varied by approach angle for any large-footprint sprite. Replaced with proper
  closest-point-on-rectangle distance (0 anywhere inside/touching the footprint) - consistent from
  every side now. Full detail in `MOD_CHANGELOG.md`.
- **Vision radius now scales with difficulty (2026-08-11 user spec)**: the existing 3-tile baseline
  is for Normal/Hard specifically now, Easy sees 4, Insane sees 2 - not the linear per-difficulty-
  step scale used elsewhere (mage cap), deliberately ties the two middle tiers together.
- **Stage 2 added (2026-08-11)**: once 80% of the map has been explored, the rest reveals outright
  instead of requiring the player to walk every last tile. Checked once per in-game day, one-shot
  (won't re-notify once already triggered). Deliberately skips the discovery-flash cosmetic layer
  above - a whole-map flash would read as noise.

### 4. Progressive Set Unlocks — `Done (playtest-confirmed 2026-08-18)` (built 2026-08-12; first playtest same day found a capitol-entry crash + several restriction bypasses — all fixed, see MOD_CHANGELOG's 2026-08-12 entry; Research Lab UI reworked per feedback)
Original idea (~100+ MTG expansions exist; player starts with access to a small subset, collect N
cards from a set to research it at a lab) combined with the user's own fuller design (2026-08-12):
color-sharded editions as the discovery mechanic, a real Research Lab screen, AI-color towns
permanently locked to their own shard. Full mechanism, design rationale, and every flagged
assumption are in `MOD_CHANGELOG.md`'s "Progressive Set Unlocks" entry - summary here:

- **Opt-in via `editionProgressionEnabled`** (new `ConfigData` flag, on in this plane's
  `config.json`).
- **6-way random edition sharding, once per new game**: every real/obtainable edition
  (`CardEdition.Predicates.CAN_MAKE_BOOSTER` + `hasBoosterTemplate`, minus `restrictedEditions` -
  the same filter the existing booster-generation code already used) is dealt round-robin across 5
  colors + "neutral" after a shuffle with the world's own seeded `Random`, persisted on `World`
  (`colorEditionShards`) for that save's lifetime. New `EditionProgression.java` owns this.
- **Discovery**: ordinary roaming-monster combat loot is restricted to the defeated monster's
  color's shard (`EnemyData.colors`, dominant/first-listed color for multicolor enemies -
  confirmed via `enemies.json` that 62% of enemies are multicolor, so requiring an exact mono-color
  match would have sent most loot to the neutral shard regardless of which color's territory a
  fight happened in, undermining the whole "explore each color" premise). Bosses and quest-tagged
  enemies are exempt (dedicated/quest rewards, per user spec) - only the generic per-enemy reward
  pool is restricted.
- **Player's own shops** (Orazca + any owned/restored town) are restricted to
  `AdventurePlayer.unlockedEditions` - starts pre-seeded with N of this plane's own curated
  `starterEditions` (4/3/2/1 by difficulty, Easy most generous - Claude's own proposal, not user-
  specified), grows via research.
- **AI-color towns are permanently restricted to their own shard** - never affected by the
  player's research progress, by design (the reason to physically travel to that color's territory
  to shop there before researching its sets yourself).
- **Mechanism reused for all three**: clone the relevant `RewardData` entries (existing copy
  constructor) and set `.editions` on the CLONE only - the originals are shared across every town/
  enemy resolving to the same shop/template name, so mutating them directly would leak. Card-type
  rewards already respect `.editions` via the existing `CardPredicate` filter; nothing new needed
  there.
- **Research Lab**: a real, pre-existing decorative building on `player_capital.tmx` (found via a
  user screenshot, not guessed - baked into the Overlay/Ground2 tile layers already, at world
  coords ~(144-192, 80-112)), wired up with a plain ungated `OnCollide` (no rubble/rebuild state,
  same "always works" pattern as the Inn) at (160, 96). New `ResearchScene` (modeled on
  `QuestLogScene`'s simpler list pattern once actually compared against `SpellSmithScene`'s fuller
  layout): shows every edition the player has found at least one card from (not yet researched),
  sorted by progress toward the threshold, grayed out below it. Threshold is 10% of that edition's
  own real card count (floor 5) - the user's own refinement over a flat "10 cards," so it's
  consistent across small and large sets. Research costs 300g (difficulty-scaled, Claude's own
  proposal) and takes 7 days; completion is automatic (checked lazily on screen-entry and from the
  daily tick) rather than needing a manual "collect" step, since there's no physical loot here.
- **Diagnostic logging** (`forge.log`, greppable): `[TFR-EditionShard]` (the full sharding once per
  new game), `[TFR-ShopEditions]` (every shop generation, its owner and restriction list),
  `[TFR-LootEditions]` (every roaming-monster reward generation), `[TFR-Research]` (start/complete/
  starting-unlock events) - this whole feature is otherwise invisible, so every decision point logs.

### 4b. Race-Based Starting Expansions — `Done (playtest-confirmed 2026-08-13)`
Your chosen race now determines which expansions you start with (user spec: "your starting race
you pick has no effect. Please assign each race a unique expansion"). Each of the 16 races has 4
lore-assigned expansions; difficulty decides how many you get: **Easy all 4, Normal random 3,
Hard random 2, Insane random 1** - so below Easy, two runs as the same race can start with
different sets. Data lives in the plane config.json's `raceEditions` (keyed by heroes.json's raw
race name); races without an entry fall back to the old flat `starterEditions` list. All 64
assignments verified to exist and have Draft booster templates (so the Capitol booster shop is
never empty at start).

**The race table (for the mod write-up):**
| Race | Expansions | Lore reasoning |
|------|-----------|----------------|
| Devil | RNA, TOR, SOI, VOW | Rakdos cultists of Ravnica; the fiends of Torment and Innistrad's devil-haunted nights |
| Kor | ZEN, BFZ, ZNR, ROE | The nomadic hook-masters of Zendikar, across all its faces |
| Human | DOM, DMU, M20, M21 | Dominaria, cradle of human civilization, plus the core sets |
| Elf | LRW, MOR, KHM, ELD | Lorwyn's perfect-obsessed elves, Kaldheim/Eldraine's fae wilds |
| Metathran | INV, PLS, APC, 8ED | Urza's blue-bred soldiers of the Invasion block |
| Undead | AKH, HOU, ISD, DKA | Amonkhet's eternalized dead and Innistrad's ghoulcalled hordes |
| Viashino | GRN, ALA, ARB, DGM | Lizardfolk of Ravnica's Gruul warrens and Alara's Jund |
| Phyrexian | SOM, MBS, NPH, ONE | The compleation of Mirrodin, start to finish |
| Dwarf | KLD, AER, KHM, BRO | Kaladesh's master artificers, Kaldheim's forge-clans, the Brothers' War machines |
| Werewolf | ISD, MID, EMN, DKA | Innistrad, all moons of it |
| Leonin | MRD, DST, AKH, IKO | Mirrodin's Razor Fields prides and Amonkhet/Ikoria's cat-warriors |
| Red Dragon | DTK, TDM, M19, IKO | Tarkir's dragonstorms, Bolas-brood core set, Ikoria's apex skies |
| White Dragon | DTK, TDM, M20, AFR | Tarkir plus D&D's chromatic/metallic dragons |
| Blue Dragon | DTK, TDM, M21, MH1 | Tarkir, core set, Modern Horizons' elder things |
| Green Dragon | DTK, TDM, IKO, KHM | Tarkir plus Ikoria/Kaldheim's primal beasts |
| Black Dragon | DTK, TDM, AFR, VOW | Tarkir, D&D's shadow dragons, Innistrad's night terrors |

### 4c. Inn Tournament Edition Lock — `Done (playtest-confirmed 2026-08-14)`
Inn tournaments (Draft/Sealed/Jumpstart events) only build from expansions the player has
researched/started with PLUS the neutral shard (the unaligned slice of the 6-way split). Gated on
`editionProgressionEnabled`; pre-feature saves are unrestricted (fail-open, consistent with the
shop restriction). A fully-emptied pool degrades gracefully to the Inn's existing "No events at
this time." Note: multi-set draft BLOCKS need every set allowed, so early-game (few unlocks)
events skew toward single-set blocks - by design, research widens the tournament scene.

### 5. Distance-Scaled AI — `Removed (2026-08-12, user decision)`
- Was: AI strength/deck gradient by distance from castle vs map center. Cut from scope; the
  bestiary difficulty tiers (#19) and territory re-theming (#7) cover the spirit of it.

### 6. Time System (Day/Night Cycle) — `Done (playtest-confirmed 2026-08-13)`
- Foundational clock built: opt-in via `config.json` → `dayNightCycleEnabled`, ~12 real
  minutes per in-game day, advances continuously while on the overworld (any pace/standing
  still), freezes automatically in towns/dungeons or while paused/in a dialog. Persisted in
  the save file. HUD readout (`TimeOfDayActor.java`) shows a plain "Day N" / "H:MM am|pm" digital
  readout near the minimap - the originally-planned crossfade dial/needle/castle-icon widget was
  simplified away to this before it shipped, this doc just hadn't caught up.
  **Restyled (2026-08-05)** to use the same `windowMain10Patch` stone-block panel every dialog/
  window in the game already uses, instead of a hand-drawn flat box - same treatment given to
  the Lumber/Stone readout, #9, plus 6px padding so the text clears the panel's border instead of
  running up against it. **Repositioned twice same day** - first to between "Wait" and "Zoom"
  per an annotated screenshot, then back to directly below "Zoom" per a follow-up correction
  (that first move wasn't actually what was wanted, once seen in place).
- **Day/night terrain life modifier SHIPPED (2026-08-12, user spec)** - the first real consumer
  of the clock: overworld roaming fights only (not Arena/Inn/dungeons), by the CURRENT terrain
  color under the fight. Day (6am-6pm): White terrain +10% enemy life, Green +5%; Black -10%,
  Red -5%. Night (6pm-6am): flipped. Blue/neutral/player terrain unaffected. Deltas ceil()'d
  ("rounded up"), floor 1 life. isNight()'s boundary moved 20:00 -> 18:00 to match the spec.
  Greppable as [TFR-DayNight] in forge.log.
- Still to do: quest timers; periodic events (trigger every N days, etc) — deferred to follow-up
  passes once the clock itself is proven out.
- Added a temporary "10x Speed" HUD checkbox (same slot the fog-of-war debug toggle used to
  occupy) to fast-forward the clock for testing - useful now for the day/night cycle, and will
  help test #7's multi-day attack cadence once that's built. Only speeds up time advancement,
  nothing else. Remove once these features don't need frequent manual speed-up.

### 7. Dynamic Territory Control — `Done (playtest-confirmed 2026-08-13)` (spatially-aware placement redesign added 2026-08-06, extended to daily expansion same day - caused and fixed a freeze, found and fixed a pre-existing doodad/ownership mismatch bug, fixed a day-reset bug and minimap staleness, then capped captured-town protection radius and fixed a stale-doodad-cache-on-load bug; 2026-08-10 - cross-color targeting activated, mages persist through a loss, Capitol defense forced duel built)
Full design worked out 2026-08-03 - detailed enough to build from. First real slice built
2026-08-05 (opt-in via new `territoryControlEnabled` flag), through 4 rounds of same-day
playtesting/fixes - **current approach, as of the 4th round** (earlier rounds tried shrinking each
color's own world-gen `width`/`height` biome parameters directly; reverted - see
`MOD_CHANGELOG.md`'s "world-gen approach redesigned" entry for the full story of why):

- World-gen runs completely normally/unmodified - every color gets its usual full-size territory,
  starter towns, and dungeons, exactly like every other plane. Immediately afterward, a new sweep
  (`TerritoryControl.neutralizeAfterGeneration()`) repaints each color's territory back to neutral
  everywhere except a radius around its own castle, and converts that color's own Town/Capital
  POIs outside that radius into their Waste Town equivalent. Every other POI type (dungeons,
  caves, forts, boss encounters - including the Planeswalker side-bosses/Story content an earlier
  round's approach was deleting outright) is left alone, still color-flavored, just now sitting on
  repainted-neutral ground.
- Each color independently sends a real, visible, fightable mage (reusing the existing "Adept
  `<Color>` Wizard" enemies, now with their own colored minimap dot too) at a random 2-5 day
  interval toward one of its **5 nearest neutral towns, measured from ANY property it currently
  owns** (castle + its towns/capitals; was 3-nearest-from-castle until 2026-08-08 - the target
  frontier widens as a color grows). **The mage always LAUNCHES from the castle though** (user
  refinement, same day, replacing a brief launch-from-nearest-property version): the long travel
  distance is deliberate, it's what gives the player a real window to see the attack coming and
  intercept it. No castle -> that color can't attack. Reaching the town transforms it into a
  genuine instance of that color's own town (real map/shops/theme, not a reskin - see
  `PointOfInterest.transformInto()`), plus recolors the surrounding terrain via the already-built
  repaint prototype.
- Only ever targets neutral towns (including player-restored ones, deliberately - confirmed with
  the user, eventually meant to be gated by a reputation scale once #1 exists, not built yet) - the
  ally/enemy color-wheel targeting and 50/50 recapture logic below are still unbuilt, only relevant
  once a color can attack *another color's* town, which this slice doesn't do yet.
- **Bugs found and fixed across the 4 playtest rounds** (all detailed in `MOD_CHANGELOG.md`): a
  world-gen hang (two pre-existing engine bugs in the wave-function-collapse structure generator,
  only ever exposed once a biome region got small enough under the since-reverted approach);
  castles invisible on the real map (Shandalar's own main-story quest-gate, removed for the 5
  castle entries only); mages despawning via the ordinary roaming-monster lifetime timer before
  ever reaching their target (fixed - mages are now exempt).
- Day length dropped 12->10 min/day per request. `count towns` (debug console, **F9/F10** to open)
  shows the actual on-map town count/breakdown. `TerritoryControl` posts on-screen notifications +
  `forge.log` lines for mage dispatch/capture, for diagnosing "is this actually firing" without
  being able to run the game directly.
- Tunable first-guess constants, not yet validated by playtesting: `CASTLE_KEEP_RADIUS_TILES`
  (`20`, was `40` before Territory Expansion also adopted it as the starting radius), mage arrival
  distance, the 2-5 day dispatch interval, `EXPANSION_TILES_PER_DAY` (`3`), `MAX_TERRITORY_RADIUS`
  (`300`), and `PLAYER_KEEP_RADIUS_TILES` (`20`, replaces the old 15-tile spawn-protection buffer -
  see Territory Control playtest round 7 entry below).
- **Fifth playtest round** ("map looks much better"): fixed minimap town icons getting partially
  painted over by the neutralize sweep's own terrain repaint (`World.redrawAllPoiMarkers()`, runs
  after the sweep); every color now guaranteed a Capital within its kept territory even if the
  real one didn't survive the sweep (`TerritoryControl.ensureCapital()`); added a live town-count
  HUD panel below the resource readout (`TownCountActor.java`, 6 rows - 5 colors + still-neutral -
  new `color_icons.png`/`.atlas` cropped from `common/sprites/items.png`); 10x speed toggle raised
  to 50x. **That same round shipped a real crash**, found and fixed immediately after: the new
  `TownCountActor` called `world.getAllPointOfInterest()` from `GameHUD`'s constructor, which runs
  once as part of opening Adventure mode itself - *before* the player has picked New Game/
  Continue/Load - so `World.mapPoiIds` wasn't populated yet, NPEing and leaving the whole menu
  unresponsive. Fixed at the source (`World.getAllPointOfInterest()` now null-safe), not just
  worked around locally.
- **Sixth playtest round** ("seeing the AI take over the map looks so cool"): replaced
  `TownCountActor`'s always-visible HUD panel with a dedicated full-screen `WorldStandingsScene`
  (own JSON layout in the mod's plane folder, opened via a new "World" HUD button) per user mockup
  - the panel was taking up too much space for data that rarely changes. Confirmed mages flying
  straight over water/terrain to their target is an intentional first-pass simplification (no
  pathfinder built for this), not a bug.
- **Territory Expansion, same (sixth) round:** the user's other big ask that round - the ground
  *between* towns stayed permanently neutral even after a color owned every town nearby. Each AI
  color's territory now slowly grows outward from its own castle every in-game day, claiming only
  currently-neutral wasteland (never another color's already-claimed land - two expanding circles
  simply stop at each other, forming a border) via a new `TerritoryControl.processTerritoryExpansion()`
  tick and `World.claimWastelandRing()`. A 15-tile buffer around the player's Spawn point is
  protected from being swallowed by a nearby color's growth. Player-color expansion (the
  7th-color/gold-tint biome) is a deliberate follow-up, not built this round - it needs its own
  anchor-point design first, since the player can restore multiple towns where each AI color has
  exactly one fixed castle. Full engineering detail (constants, the "first claim wins" no-overlap
  reasoning, what's still out of scope) in `MOD_CHANGELOG.md`'s "Territory Expansion" entry. Not
  yet playtested - needs a fresh world (existing saves won't have the new per-color radius state
  seeded) and several in-game days fast-forwarded at speed to see the effect.
- **Terrain Switch-Out, same day, re-examined from scratch per user request:** feedback on
  Territory Expansion identified the real weak point of the whole feature - "the terrain switch...
  once the over-ride happens, it feels flat." Root cause: every repaint (the initial neutralize
  sweep, expansion, and individual town captures) deleted a tile's mountains/rocks/trees/water
  outright (`terrainMap[x][y] = 0`) instead of reskinning them, since a raw structure index is only
  meaningful under the specific biome that generated it. Confirmed the doodads (small scatter
  decorations - rocks/flowers/stumps) were already a shared, generic sprite catalog swapped
  correctly per-biome; the actual gap was the bigger WFC-placed *structures*. Fixed by adding a
  translation layer (`World.translateStructure()`/`buildStructureSwapTable()`/`pickReplacement()`)
  that reskins a repainted tile's existing structure to the new biome's closest equivalent -
  exact-name match first (biomes already share a lot of literal names like `rock`/`tree`/`tree2`),
  then a thematic category (`STRUCTURE_CATEGORY`, e.g. `mountain`/`mesa`/`plateau` grouped
  together), then a universal `rock` fallback (present in every one of today's 6 core biomes, so
  it never bottoms out) - preserving the WFC-generated shape/footprint exactly, only changing which
  biome's sprite renders it. All 3 repaint call sites (`repaintBiomeAroundTown()`,
  `neutralizeTerritoryOutsideRadius()`, `claimWastelandRing()`) now go through this shared
  translation instead of zeroing. Full design writeup and code-level detail in `MOD_CHANGELOG.md`.
  Not yet playtested - needs a fresh world (a loaded older save's already-repainted areas keep
  whatever they had when saved).
- **Territory Control playtest round 7, same day:** border-seam/wedge artifacts (a color slicing
  through another color's territory) and the player's home base getting visually "ringed" by
  whichever AI color reached its static protection bubble first - both traced to the same root
  cause (territory claims only checked "am I within my own radius," no awareness of any other
  color's circle) and fixed together: `World.claimWastelandRing()` now only claims a tile where its
  own anchor is the *nearest* of all 5 castles **and** the player's Spawn (a Voronoi-style
  assignment), replacing the old flat Spawn-protection hack. The player also now gets a real
  starting circle around Spawn at world-gen end (parity with an AI color's own kept circle,
  `PLAYER_KEEP_RADIUS_TILES` = 20, not yet growing over time - a smaller follow-up), and
  `player.json` gained real (gold-tinted, reused-from-colorless) structures so captured towns
  inside AI territory now fully reskin to the player's own color instead of leaving some AI-colored
  structures behind. Also fixed: the "World" HUD button no longer shows inside a town; the World
  Standings icon crop (still misaligned after the first attempt) now uses exact coordinates read
  off the source sheet; added a 7th "Player" row to World Standings using
  `TownRestoration.isTownRestored()` as the count and the minimap's own player-marker texture as
  the icon. Explained but not built: the minimap has never shown individual doodads/structures for
  any biome (confirmed pre-existing engine behavior, not a regression). Expansion speed left
  untouched per explicit user request (easier to observe progression while testing). Full
  engineering detail in `MOD_CHANGELOG.md`. Not yet playtested.
  - **Corrected same day, fast first-look feedback:** the player does NOT get a free starting
    circle - "the player should only start once he takes his first city." Removed the one-time
    world-gen-end claim; Spawn still blocks AI encroachment via the nearest-anchor check, it just
    never paints anything until an actual capture does. Also fixed the World Standings "Player"
    icon (was a generic minimap dot, now the player's real chosen avatar,
    `Current.player().avatar()`). Bigger finding: resource files (JSON/PNG/atlas) were never
    actually syncing to the deployed game (`E:\GAMES\FORGE\res\` is a separate copy, not a live
    view of the repo) - this alone likely explains the "captured town doodads stayed wasteland/
    green instead of player color" report, since the deployed `player.json` had no structures the
    whole time it was tested. Resynced; now a standing required deploy step (see
    `MOD_CHANGELOG.md`'s Toolchain section). Asked the user to retest before assuming anything else
    needs to change there.
  - **Round 8, next day - confirmed the icon/avatar fixes worked, four more fixes:** World
    Standings now ranks the 5 AI colors by town count (Colorless pinned last). Fixed a real,
    unrelated-to-Territory-Control bug the user diagnosed themselves - `ShopActor` was drawing a
    fallback building icon over every shop unconditionally, duplicating the baked-in art every
    AI-color town template already has (only the wasteland/player-rebuilt template actually needs
    the fallback). Leading hypothesis for the recurring "blue border" - fog-of-war's haze tint had
    a slight blue color bias, removed. Added a full-map doodad regeneration pass to the one-time
    neutralize sweep (structures were already being reskinned correctly, doodads weren't touched
    at all) - directly responds to a detailed user report about the map not "feeling like one
    continuous area" outside AI keep circles. Explicitly NOT fixed, flagged honestly instead:
    structure *density/pattern* in swept territory still reflects whichever color originally
    generated it (the chosen design preserves WFC footprint exactly rather than re-deriving
    placement) - the doodad fix helps but doesn't fully close this gap. Full detail in
    `MOD_CHANGELOG.md`. Not yet playtested - needs a fresh world.
  - **Generate-as-wasteland redesign + road-bit preservation, next day (2026-08-06):** the "blue
    border on roads" report and round 8's own honestly-flagged density/pattern gap above both
    traced to the same underlying cause - each AI color generating and then mostly discarding its
    *own* full-size WFC territory, rather than the swept area ever actually being generated as
    wasteland. Redesigned per a user proposal, refined during planning: each color's
    terrain/structures/spriteNames are now temporarily pointed at colorless's own for the whole
    duration of world generation (`World.swapColorsToWastelandContent()`/
    `restoreColorsRealContent()`), so the swept ~95% of a color's claim is generated using
    wasteland's actual recipe from the start, not a differently-patterned territory later reskinned
    - then each color's real starting circle is claimed back with real content via the
    already-proven `claimWastelandRing()`, once generation finishes. Territory shape/extent and
    castle/POI placement are completely unaffected (only which *content* a color generates with
    changed, not where/how much). Separately, fixed the road-tracing border itself: verified
    (unlike the reverted ocean-bit attempt) that a road tile safely carries its road bit through a
    normal repaint instead of needing to skip the tile outright, since road has exactly one
    renderable region and its terrainMap value is always 0 - all 3 repaint methods updated
    accordingly. Full engineering detail (including a `structureSwapCache` staleness bug caught and
    fixed during planning, before it ever shipped) in `MOD_CHANGELOG.md`. **Not yet playtested** -
    needs a fresh world; this is a real architecture change to world generation, first time tested.
    - **First playtest, same day: found and fixed a real bug, not a doodad issue.** All 5 AI
      circles (not just the area outside them) came out flat/structure-less, only the central
      wasteland core looked right. Root cause: the swap above shared `structures[]` object
      references across 5 colors + colorless, but the WFC pattern cache (`structureDataMap`) is
      keyed by *object identity* and builds one pattern per biome sized to *that biome's own*
      width/height - sharing objects meant 6 biomes raced to store differently-sized patterns
      under the same keys, and whichever won (likely colorless's, the largest biome) got queried
      by every other biome's per-tile placement using the *wrong* coordinate system, silently
      dropping almost every structure. Fixed by cloning `structures[]` per color instead of
      sharing it (`terrain`/`spriteNames` sharing is unaffected - confirmed safe by reading their
      own consuming code, no identity-based caching there). Also fixed a small pre-existing gap
      this surfaced: the clone constructor being newly relied on had never copied the WFC
      pattern-size field (`N`). Full mechanism in `MOD_CHANGELOG.md`. Not yet re-verified - needs
      another fresh world.
    - **Second playtest, same road-border fix confirmed working; circles still flat - real fix,
      not another reskin.** The `structureDataMap` fix above was necessary but not sufficient:
      `claimWastelandRing()`'s reskin can only recolor whatever structure a tile already has, never
      add density that wasn't baked in - and every tile in a circle was generated using colorless's
      own (sparser, by design) WFC pattern. Fixed with a new `World.regenerateStructuresForClaim()`,
      called once per color right after `claimWastelandRing()` in the one-time world-gen claim only
      (daily expansion still calls `claimWastelandRing()` alone, unchanged) - builds a fresh WFC
      pattern from the color's own real `structures[]` and replaces the reskinned structures with a
      genuine placement. **Also fixed, a separate report the same round**: AI expansion could grow
      around a town the player personally captured away from Spawn - a known, already-deferred gap
      from Territory Expansion's original design, not something this redesign broke. Per user
      decision, every player-owned town is now a protected rival anchor for daily expansion, not
      just Spawn. Full detail in `MOD_CHANGELOG.md`. Not yet re-verified - needs another fresh
      world for the structure fix; the anchor fix works on an existing save.
    - **Third playtest: still flat - root-caused to a structural ceiling, not a bug, and fixed by
      replacing the whole-biome swap.** `regenerateStructuresForClaim()` was confirmed working
      exactly as designed (15-32% structure placement per `forge.log`, no errors) - the problem was
      that sampling a small ~40-tile window out of a WFC pattern can never look as dense as content
      actually generated at full scale, no matter how it's tuned. Replaced the whole-biome content
      swap with spatially-aware placement: `generateNew()`'s per-tile loop now computes each AI
      color's real content within `CASTLE_KEEP_RADIUS_TILES` of its real castle (known precisely,
      not predicted - planned via an Explore + Plan agent specifically to rule out a predicted-vs-
      actual mismatch that would have been a real rendering bug) and colorless's own content
      everywhere else in that color's claim, natively, the first time - no reconstruction needed
      anymore. `regenerateStructuresForClaim()` and the whole-biome swap mechanism are both removed.
      Full design and mechanism in `MOD_CHANGELOG.md`. **Not yet playtested** - the fourth attempt
      at this exact density problem, and the first to remove the structural reason the earlier ones
      were capped rather than trying to improve the sampling.
    - **Fourth playtest: confirmed working for 4/5 colors** (white/blue/red/green all dense and
      correct - the density problem itself appears solved). Black specifically still showed a gap -
      investigated and compared directly against red's (working) data, found no structural
      difference, leading hypothesis is ordinary WFC pattern variance for this specific seed rather
      than a bug, not yet confirmed either way. Two real, unrelated minimap gaps found and fixed:
      the minimap can now be explicitly re-baked from final state after Territory Control's sweep
      (`World.rebakeMinimapAfterTerritoryControl()`, requested directly), and a town's minimap icon
      no longer gets painted over and lost after a live capture (AI or player) - a pre-existing gap,
      not caused by either placement redesign. A water/road border reported in a few places is not
      yet investigated - asked for a more specific repro before guessing at it. Full detail in
      `MOD_CHANGELOG.md`.
    - **Fifth playtest: both minimap fixes confirmed working; black's gap and white's flat minimap
      root-caused to daily expansion, not world-gen - fixed by extending Pass B's approach to
      `claimWastelandRing()`.** Reported more precisely this round: black was "skipping an area with
      the fill" while still placing doodads there, and white's minimap looked flat "where it
      spreads" despite real content existing on the actual map - both describe territory *outside*
      the initial circle, i.e. tiles claimed by daily expansion, not world-gen's own placement.
      Root cause: `claimWastelandRing()` still built claimed tiles via `translateStructure()` (a 1:1
      reskin of whatever wasteland's own WFC pattern already had there) - the exact same density
      ceiling Pass B was built to eliminate for the initial circle, never extended to daily growth.
      Fixed by giving `claimWastelandRing()` the same native-computation approach, via three new
      lazily-built persistent caches on `World` (a structure-pattern cache, a shared noise instance,
      and a per-color colorless-redirect-structures cache - all needed since `generateNew()`'s own
      versions of these are local variables, unreachable from gameplay-time calls, and must also
      work for a game loaded from a save, which never calls `generateNew()` at all). Pass B itself
      now shares the redirect-structures cache too, so the initial circle and its later expansion can
      never independently drift on what "outside-radius content" means for a color. Full mechanism
      in `MOD_CHANGELOG.md`. **Shipped a real regression, found and fixed the same round**: loading
      an existing save froze the game after a little while - `forge.log` showed white/blue/black's
      daily expansion completing normally, then nothing where red's line should be, and no java
      process left running afterward. Root cause: the fix above ran a genuinely heavy WFC computation
      synchronously on the game's main/render thread, the first time each color's pattern was needed
      - safe during `generateNew()` (a loading screen the player expects, and parallelized there for
      a color's own real structures), not safe mid-gameplay with zero warning. Fixed by making that
      computation build on a background thread instead, with `claimWastelandRing()` never blocking on
      it - if a color's pattern isn't ready yet, that day's claim still happens with correct ground/
      collision, just without decorative structures until the background build finishes (self-
      correcting, at most a one-time, one-ring cosmetic gap per color per game session). A new
      `World.prewarmTerritoryControlCaches()`, called right after a save loads, gives all 5 colors'
      builds a head start so this case is rare in practice. Full detail in `MOD_CHANGELOG.md`.
      **Confirmed fixed** by re-testing the same save - `forge.log` showed all 5 colors completing
      normally across 5 in-game days, no freeze, no exceptions.
    - **Sixth playtest: freeze confirmed fixed, but re-testing surfaced the real cause of "black
      doesn't close up" - a pre-existing bug, not a density issue.** Described precisely this round:
      "a visible chunk of the circle - some doodads did spread there from black, but the terrain
      never changed color... looks like a section of a perfect circle." Root cause:
      `regenerateDoodadsInRadius()` (places a color's decorative doodads after
      `claimWastelandRing()`'s own ground-ownership loop runs) only ever checked the plain geometric
      radius, never the nearest-anchor (Voronoi) check the ownership loop also applies - so a tile
      geometrically in range but actually closer to a neighboring color's castle got that neighbor's
      ground correctly, but still got *this* color's doodads placed on it (and had whatever
      legitimate doodads it already had incorrectly stripped). A straight Voronoi boundary between
      two castles cuts a flat chord out of a circle - exactly the reported shape. Pre-existing since
      nearest-anchor claiming was first added to the ground loop, well before this week - not caused
      by any of this session's recent rounds. Fixed by having `claimWastelandRing()` hand its own
      loop's exact claimed-tile set directly to doodad placement, instead of that method
      independently re-deriving (and getting wrong) the same answer a second time. Full detail in
      `MOD_CHANGELOG.md`. **Not yet playtested** - only prevents the mismatch going forward; existing
      mismatched tiles from before this fix won't self-correct without further expansion attempting
      to reclaim that area (unlikely, since ground ownership there is already someone else's).
    - **Seventh playtest: black's irregular (non-circular) shape explained, not a bug - confirmed the
      user's own hypothesis that a player-owned town blocks AI expansion around itself, unbounded.**
      Every player-owned town (not just Spawn) is a permanent rival anchor in the nearest-anchor
      check, with no radius cap of its own (unlike an AI castle's `CASTLE_KEEP_RADIUS_TILES`) - a
      real design decision from earlier this session, working as intended, though possibly worth
      revisiting later (should a captured town's protection be bounded instead of an unbounded
      Voronoi cell?) - a balance question, not fixed this round. **Three separate, real bugs found
      and fixed alongside this**: (1) `World.generateNew()` never reset `dayCount`/`colorNextAttackDay`/
      `colorTerritoryRadius` - only `load()` did, so starting a new game without restarting the app
      inherited stale state from the previous session (confirmed: a fresh game started on day 31,
      matching a prior save). (2) The corner minimap's texture only re-snapshots on HUD entry, never
      while the player just stays on the overworld screen as daily expansion keeps editing the map in
      the background - the actual explanation for "map details still being wiped out on the mini-map
      by the expansion creep" (not fog of war, ruled out directly by the user for an earlier report).
      Now refreshes once per in-game day instead. (3) `FAST_TIME_MULTIPLIER` raised 50x -> 100x per
      explicit request to speed up testing. Full detail in `MOD_CHANGELOG.md`. **Not yet playtested.**
    - **Eighth playtest: captured-town protection capped to a fixed radius (user decision, replacing
      last round's unbounded design); a real stale-doodad-across-load bug found and fixed; two threads
      left open pending more information.** A controlled A/B test (save before capturing a town near
      black, capture it, reload the pre-capture save) confirmed last round's explanation was right in
      kind - the reported "perfect circle, center not the town" is the mature form of the same
      unbounded-rival-anchor mechanic: once a color's disc grows past the town on every side, its small
      protected cell becomes a fully-enclosed island rather than just a bite out of the edge. Given the
      choice, capped a captured town's protection to `CASTLE_KEEP_RADIUS_TILES` (20 tiles, same as an
      AI castle's own) instead of leaving it unbounded - `claimWastelandRing()` now takes a separate
      `boundedRivalAnchors` parameter for this, distinct from the still-unbounded `otherAnchors`
      (other AI castles + Spawn, which need to stay unbounded for clean color-vs-color borders and
      permanent home-base protection). **Separately, a real bug**: loading an earlier save via the
      in-game Load menu (not an app restart) while standing at the same spot still showed doodads left
      over from the later, abandoned session - `WorldBackground`'s per-chunk decoration Actor lists are
      long-lived-singleton-cached and were never being invalidated by a plain load. Fixed by forcing
      every chunk to rebuild its Actor list right after a load, reusing the existing (already proven
      safe) `reloadBackgroundChunkObjects()` mechanism. **Two threads investigated, not fixed, pending
      more information**: the minimap "still covered by white" report (confirmed to be on the build
      with last round's refresh fix already - leading alternate theory is the minimap's always-flat
      per-tile icon design, not a staleness bug, but not yet confirmed against a screenshot showing
      that specific contrast); and "spread didn't resume after an AI took the city back" (checked
      whether a stale `TOWN_RESTORED_FLAG` could be the cause - directly refuted by reading
      `PointOfInterest.transformInto()`, which gives a recaptured town a fresh id/state by design - the
      real explanation is still open). Full detail in `MOD_CHANGELOG.md`. **Not yet playtested.**
    - **Five-request round (2026-08-07, "things are really progressing well")**: standings
      Reputation/Status columns moved next to Town Count with aligned right-justified numbers and
      matching font; `MAX_TERRITORY_RADIUS` 300 -> 450; captured towns now grow their own territory
      (10 -> `TOWN_MAX_TERRITORY_RADIUS` 15, per-town persisted radius, protection cap follows the
      current radius; a planned "outlook" building will later raise it further); mage dispatch
      notification appends a bold red "Player Owned!" when the target is the player's; fog-of-war
      three-tier rule - mage dots on both minimaps only show in REVEALED territory, and the area
      around player-owned towns counts as Revealed at the town's current radius (also kept
      explored); per-color simultaneous-mage cap scales with difficulty (Easy/Normal/Hard/Insane ->
      2/3/4/5). Full detail in `MOD_CHANGELOG.md`. **Not yet playtested.** An adversarial
      verification pass before commit caught 9 findings in this round (one high: an AI capturing a
      GROWN player town would have stranded the grown ring in player color forever) - all fixed in
      the same round, see `MOD_CHANGELOG.md`'s findings entry.
    - **Blue border, last holdout fixed (2026-08-07)**: confirmed fixed on all AI territory, still
      present around PLAYER captured towns - `repaintBiomeAroundTown()` now keeps the waste bit
      underneath a player-over-former-waste repaint (safe for player specifically: its tables are
      exact colorless clones), completing the dual-bit fix's coverage. **Not yet playtested.**
    - **Radius mismatch caught immediately after shipping**: asked directly whether the new 20-tile
      protection cap matched the actual terrain-recolor radius on capture - it didn't
      (`TerritoryControl.RECOLOR_RADIUS` is 10 tiles, half of `CASTLE_KEEP_RADIUS_TILES`), leaving an
      invisible 10-tile buffer ring around every captured town. Given the choice, shrank protection to
      match the recolor radius (10 tiles) instead of growing the recolor or leaving the mismatch.
      `RECOLOR_RADIUS` made `public` so `World.claimWastelandRing()` can reference the same number
      directly. Full detail in `MOD_CHANGELOG.md`. **Not yet playtested.**
    - **Deep dive (2026-08-07, home PC): the two longest-standing visual issues both root-caused
      and fixed - minimap detail wipe on spread, and the blue "water" border along roads/spread
      edges.** Minimap: all 3 repaint paths were stamping a flat base pixel per touched tile,
      erasing the terrain-variant/structure/road detail the post-sweep rebake had put there - now
      they redraw each tile's real content (`World.redrawMinimapTile()`), and daily expansion also
      re-draws POI marker icons it sweeps past (was clipping them). Blue border: root-caused to
      the renderer's fallback promoting the ocean layer (literal blue water, bit 0 under every
      world-gen tile) to the visible base wherever a single-bit claimed tile broke its neighbors'
      (and skipped road tiles') full-neighborhood checks - fixed for daily expansion by keeping
      the colorless bit UNDER the claimed color's bit, restoring the same multi-bit blending
      stock world-gen boundaries use (also softens the claim edge into a real transition).
      Captures (`repaintBiomeAroundTown()`) deliberately not given the same treatment yet - real
      index-range wrinkle documented in `MOD_CHANGELOG.md`, needs its own pass. **Not yet
      playtested - needs a fresh world for the full effect; pre-fix claimed tiles in an existing
      save keep their old single-bit state.**

**More raised by the user (2026-08-05), not scoped or started - recorded so they aren't lost,
needs its own design pass before any of this gets built:**
- **A way to handle newly-added items.** Not yet clarified whether this means player-facing items
  (equipment/potions - #10's item shops), or new POI/content types being added to the world over
  time (ties into the next point) - ask before scoping this one, the request as given covers both
  readings.
- **A way to handle color-specific "special" POIs** (Groves, Vampire Castles, Merfolk Pools, the
  Planeswalker side-bosses, etc). **Overtaken by the world-gen redesign later the same day** - the
  approach that deleted these outright was reverted; they now generate normally and simply sit on
  repainted-neutral ground outside a color's castle radius. Still an open, unbuilt question though:
  should they eventually change/appear differently based on which color controls the surrounding
  territory (dynamically tied to ownership), or is "unowned dungeon on neutral ground regardless of
  its own color flavor" fine indefinitely? The former would be a real, separate feature on top of
  what exists now, not a prerequisite for anything currently built.
- **Quest expiration timer**: a configurable number of days a quest stays active before it fails
  automatically. This is the concrete version of something #6 (Time System) already listed as
  unbuilt ("quest timers") - worth building against #6's existing day-counter hook
  (`WorldStage.onActing`'s `dayAfter != dayBefore` pattern `TerritoryControl`/`EconomyBuildings`
  already use) rather than a new clock.
- **Dungeons/caves spawning and despawning over time**, not just fixed at world-gen - part of
  making the world feel alive/changing on its own, a distinct idea from territory *ownership*
  (which only ever affects towns right now, never dungeons).
- **Resource nodes (Stone/Gold/Lumber/Shards) spawning and despawning on the overworld map
  itself.** Worth clarifying how this differs from #10's Economy Buildings before building -
  #10 already produces all 4 of these resources passively once a building is constructed; this
  sounds like a *separate* mechanic (physical pickups appearing/vanishing on the map you walk up
  to), not a variation of #10 - but that distinction should be confirmed with the user, not assumed.
- **Audit needed: special bosses/boss dungeons vs. the new dynamic world.** Checked while
  recording this list - a real, concrete finding, not hypothetical: several of the POI types
  zeroed out this round for world-gen (see removed-POI list above) aren't generic filler, they're
  tagged real boss/story content - `Tibalts Fortress`/`Zedruu City`/`Nahiri Encampment`/
  `Kiora Island`/`Jacehold`/`Teferi Hideout` (all `type: sideboss*`, tagged `Boss`+`Planeswalker`),
  `Grolnoks Bog`/`Slimefoots Lair` (named `Boss` encounters), and `Temple of Chandra`/`Temple of
  Liliana` (tagged `Boss` **and** `Story`). These are currently just gone from the map on a
  Territory-Control world, same as the generic Cave/Fort filler - likely wants prioritizing ahead
  of the generic content in whatever "re-add removed POIs" follow-up happens, rather than being
  treated the same as ordinary filler dungeons. Each castle's own main-story boss (the
  `Chapter1Boss`/`Boss` tags on the "`<Color>` Castle" POIs themselves, e.g. Black Castle) is
  *not* affected - castle entries were never touched, only their non-castle POI lists were zeroed.

- **Start state:** map generates 100% neutral/colorless. Every town starts broken (ties into
  #2 - this is the same "destroyed" state `TownRestoration` already models, just now something
  colors actively fight over instead of only the player rebuilding).
- **Attack cadence:** every 3-4 in-game days (ties into #6's clock), each of the 5 colored
  Castles sends a unit at one of its 3 closest towns it doesn't already own, chosen randomly
  among those 3. Recommend independent per-color timers (each color rolls its own 3-4 day
  cooldown) rather than all 5 firing in lockstep - reads as more organic. **Built as random 2-5
  days** (close enough to the "3-4, recommend independent timers" spec above - each color rolls
  its own delay independently).
- **Ally/enemy targeting:** a color's targets are limited by the existing color-wheel table
  (top of this doc) - e.g. Green never attacks a White or Red town, only Black or Blue ones.
  This applies to attacking *other colors'* towns; attacking neutral/unowned towns isn't
  restricted by the wheel.
- **Race condition:** if two colors target the same town in the same window, whichever unit
  arrives first gets it.
- **Units are visible on the overworld** - an actual sprite travels from castle to target over
  the following days, not resolved invisibly in the background. The player can intercept and
  fight it; doing so successfully grants a reward (exact reward TBD).
- **Capture resolution:**
  - Attacking a *neutral* town: succeeds, town flips to the attacker's color, map art updates
    to match (see technical note below).
  - Attacking an *enemy-color* town: 50/50 either the town flips to the attacker's color, or it
    reverts to neutral/broken instead of changing hands directly color-to-color. The revert
    case is deliberate - it's what gives the player a window to step in and claim/restore a
    contested town themselves rather than territory just ping-ponging between the 5 colors.
  - **Player-restored towns and fortifications (ties into #8):** if the player has restored a
    town (#2) but left it unfortified, a successful capture wipes that progress - the town
    becomes the attacking color's version, restoration/shops reset. Fortifications (#8) exist
    specifically to prevent this: a fortified town has a high chance to repel the attack
    outright, protecting the player's investment. This is the whole reason #8 needs to exist,
    not just a flavor upgrade.
- **Technical risk, flagged before implementation starts:** the overworld's terrain is baked
  once at world-gen (`World.java`'s `biomeMap`/`biomeImage`), not tagged per-town - recoloring a
  captured town means repainting a *region* of that baked terrain, not just swapping an icon
  (icon swaps are what we've done so far, e.g. `wastetown_broken`, and are cheap; region
  repainting is not). Recommended approach: pre-split the map into per-castle zones at
  generation time (Voronoi-style, castles as seed points) and precompute each tile's
  neutral-appearance *and* each relevant owned-by-color-X appearance up front, so a capture just
  switches which precomputed variant renders - no live regeneration. This is a world-gen
  redesign and should be scoped/built before the attack/capture logic depends on it.
  - **Good news found while researching this:** the engine already generates the base 5-color +
    neutral layout this exact way. Each `world/biomes/*.json` file has `startPointX/Y` (a
    normalized anchor point in the map) plus `noiseWeight`/`distWeight`, and world-gen assigns
    each tile's biome by combining noise with distance-to-anchor - i.e. it's already a
    Voronoi-ish, anchor-point system, just not one that can be *changed* after generation. The
    pre-split-zone idea above isn't a new algorithm, it's making the existing one dynamic.
- **Terrain-repaint prototype built** (2026-08-03): `World.repaintBiomeAroundTown()` proves the
  live-repaint mechanism works, wired to fire when the player restores a wasteland town's Job
  Board (hardcoded to always recolor "green" for testing, not real color-selection logic yet).
  Deliberately crude - hard-edged, no autotile blending, ignores roads under the patch - see
  `MOD_CHANGELOG.md` for exactly what's simplified. This validates the mechanism, it is *not*
  the pre-split-zone system above - that's still the right approach before the real
  attack/capture logic gets built.
- **First playtest fix:** the recolored ground looked right, but old-biome decorations
  (wasteland's dead-tree/crater doodads) stayed scattered on top of it - two separate systems
  place things on the ground, and the prototype only touched one (ground terrain, not the
  independently-cached decoration objects). Fixed by regenerating decorations with the target
  biome's own placement rules instead of trying to translate old ones - see
  `MOD_CHANGELOG.md`. Structures (dead trees/craters specifically) are cleared but not yet
  regenerated - recolored patches currently get doodads (rocks/flowers/etc) but no structures.
- **Player gets a 7th color** (new, 2026-08-03): alongside the 5 AI colors + neutral Wasteland,
  the player will have their own distinct territory color/terrain. Surveyed the other bundled
  planes for reusable art - **none had anything usable**: Realm of Legends and Crystal_Kingdoms
  have no custom terrain art at all (Crystal_Kingdoms is a pure name-reskin of the existing 6
  slots); Innistrad only reskins structures/decorations, not ground; Amonkhet fully reskins
  ground art but only recolors the existing 6 slots, doesn't add a 7th. Real new pixel art is
  needed either way - Amonkhet's `autotiles.png`/`terrain.atlas` pair is the right *technical*
  template for adding a new tileset slot (same schema, no code changes needed to register it,
  same as how the other colors work), and Innistrad's structure-masking system
  (`structureAtlasPath`/`maskPath`/`mappingInfo`) is the right template for giving Player
  territory its own decorations (banners/watchtowers/fences instead of reusing another color's).
  Leaning gold/heraldic as a palette direction (reads as "player/multicolor" in MTG shorthand,
  distinct from all 5 mono colors and gray Wasteland) but not committed yet.
- **Placeholder built (2026-08-04):** a real, registered `player` biome now exists - not real
  art, a programmatic gold/amber tint of Wasteland's own terrain tiles (color-multiply, not a
  hand-painted reskin). Good enough to playtest the mechanic with a visually distinct 7th color
  while real art gets sourced. Full spec for that real art is in `MOD_CHANGELOG.md`. The new
  biome deliberately claims zero territory at world generation (`width`/`height`: 0 - a biome
  registered but never placed) since Player territory should only ever come from towns the
  player actually claims, not a pre-existing map region like the 5 AI colors get.
- **Dungeons:** deliberately *not* re-themed per the biome's current color for now - re-skinning
  dungeon interiors per color would need parallel map sets (5x the content) or theme-swapping
  logic neither of which seems worth it yet. Only overworld terrain + town icon change color.
  Revisit only if this feels wrong in actual playtesting.
- **First playtest of the prototype (2026-08-04):** confirmed live repaint works. Fixed a real
  bug found in the same pass - the ruined-town art/rubble was incorrectly applying to dungeons
  too (see `MOD_CHANGELOG.md`), not just towns. Also fixed roads getting silently erased by a
  repaint. The hard-edge "looks like water in spots" rendering artifact is still there -
  confirmed as the already-documented "no autotile blending" limitation above, not a new issue,
  and still deliberately not patched (needs the pre-split-zone approach, not a quick fix).
- **Second playtest pass, same day:** the Spawn point (starting encampment/teleporter) was
  incorrectly getting the wasteland-ruin treatment too - it's legitimately `type="town"` +
  `BiomeColorless` in the game's own data, so the town/capital check above didn't exclude it.
  Worse, its normal icon is 16x16 vs. the broken art's 48x48, and the icon-swap code doesn't
  re-anchor anything, so it rendered 3x oversized and visibly offset from its real (unchanged)
  collision zone. Fixed by excluding anything tagged `"Spawn"` from `isWastelandTown()` - it's
  the player's always-safe home base, never meant to be destructible. Confirmed fixed in-game.
- **Open item - `player` biome has no curated enemy list, and hit an engine bug because of it
  (2026-08-04):** `player.json`'s `"enemies": []` doesn't mean "no enemies" - every biome always
  gets a zero-spawn-rate copy of every enemy in the game added for quest-boost purposes (see
  `BiomeData.getEnemyList()`), so an empty list means "only zero-weight ones exist," and a real
  engine bug in the weighted-selection algorithm turned that into "always the same one enemy,
  deterministically" (fixed generically in `BiomeData.getEnemy()`, see `MOD_CHANGELOG.md` - this
  bug could have affected any biome with an empty `enemies` array, not just this one). The engine
  bug is fixed, but the design question it exposed is still open: should recolored player
  territory have its own curated `enemies` list (and if so, which enemies - something
  themed/weaker, reflecting "friendly" territory?), or be intentionally enemy-free? Needs the
  user's call before `player.json` gets a real `enemies` array.
- **Decoration doodads (rocks/flowers/etc, `mapObjectIds`) now regenerate on repaint** -
  `World.regenerateDoodadsInRadius()` clears old-biome doodads in the radius and re-places new
  ones using the *target* biome's own `spriteNames`/density, called from
  `repaintBiomeAroundTown()`; `WorldBackground.reloadChunkObjects()` forces the affected chunks'
  cached decoration Actors to refresh so the change is visible without leaving/re-entering the
  area. This does **not** cover the bigger structural terrain features (dead trees/craters, from
  `BiomeStructureData`) - see the still-deferred item below for why those remain out of scope.
  - **Playtest fix (2026-08-05):** regenerated, but effectively invisible - `BiomeSpriteData`
    density values (e.g. `player.json`'s only sprite, "Stone", at 0.01) are tuned for full
    world-gen scale (thousands of tiles); over a radius-10 repaint patch (~300 tiles) that same
    density yields only ~3 doodads, easy to miss entirely and easily read as "still no doodads."
    Fixed with a `DOODAD_DENSITY_MULTIPLIER` (5x) applied only inside the repaint path, leaving
    the shared density values world-gen itself uses untouched. Applies to any repainted biome
    generically, not just `player` - relevant once more than one of the 7 colors has its own
    biome file.
  - **Playtest fix (2026-08-05):** still only rocks - `player.json`'s `spriteNames` only listed
    `"Stone"` (matching stock `colorless.json`/Wasteland's own baseline exactly, since `player`
    started as a copy of it). Added `Gravel`/`Stump`/`Bush`/`Flower` (all pre-existing sprite
    types already defined in the shared `map_sprites.json`, not new art) for visual variety - a
    content change, not a code change, so whatever collision each type already has (some doodads
    block movement, most don't) carries over unchanged, nothing about that was touched.
- **Resolved (2026-08-05, "Terrain Switch-Out" - see below and `MOD_CHANGELOG.md`):** the bigger
  structural terrain features (mountains/rocks/trees/water) used to get wiped by every repaint
  (`terrainMap[x][y] = 0`) instead of regenerated - this item used to describe that as deferred.
  Rather than the "re-run a scoped-down version of `generateNew()`'s noise selection" approach
  originally sketched here, the actual fix translates each repainted tile's *existing* structure
  into the new biome's closest named equivalent (`World.translateStructure()` and friends) -
  preserves the WFC-generated shape exactly, no re-derivation of placement needed. See "Terrain
  Switch-Out" below for the full writeup.
- **Cross-color targeting activated, mage persistence changed, Capitol defense built (2026-08-10,
  not yet playtested)** - closes out several items this section had long left unbuilt:
  - **Colors now attack each other's towns**, not just neutral ones - a color's `dispatch()`
    candidate pool now also includes ordinary TOWNS (never CAPITALS - no defined consequence
    exists for a captured AI capital) owned by either of its two ENEMY colors on the standard
    wheel (top of this doc), mixed with neutral candidates and picked purely by distance, no type
    preference. Reaching a still-enemy-owned town is a 50/50 flip-to-attacker-or-revert-to-neutral
    (the original, previously-unbuilt design above) - **note for a later pass (user request):
    revisit this flat coin flip once mage tiers/strength exist, weight the odds instead.** If the
    town changed hands to an ALLY of the attacker before the mage arrives, it fizzles silently
    (no capture, no message) rather than fighting for it.
  - **Losing to an attack mage no longer removes it.** It survives, keeps traveling toward its
    target, and simply can't be re-engaged again until the next in-game day
    (`EnemySprite.lastDuelDay`, checked in `WorldStage.onActing()`'s collision loop) - winning
    still kills it and stops the attack, same as before.
  - **Capitol targeting**: the player's own Capitol is never a normal candidate, and is fully
    exempt from a color's attacks while Partner or Happy with it. At War specifically it becomes
    attackable via a flat weight bonus worth 5% of the candidate pool's total - stacking with the
    existing player-town reputation multiplier - on top of the ordinary 5 nearest (so 95%/5% in
    the common case; the 5% bonus adds to whatever share it'd already have if it happened to also
    land among the 5 nearest, though in practice it never does).
  - **Capitol defense**: a mage that reaches the Capitol no longer captures it via the ordinary
    flow - it queues a forced best-of-3 duel (`WorldStage.startForcedCapitolDuel()`, a one-shot
    `EnemyData` clone with `gamesPerMatch=3` so ordinary mage encounters elsewhere stay best-of-1),
    fired at the next safe moment regardless of whether the player is on the overworld or inside a
    town (`GameStage.act()`, shared by both). **Losing ends the run** - closes out #13's
    long-open "game-over-on-loss still open" item. No permadeath mechanic exists in this codebase
    to hook into, so this is new: a blocking defeat dialog, then back to the main menu with the
    save left untouched (not deleted) - worth a look once playtested to see if that's the right
    weight for it. Winning defeats the mage normally (2x mage-kill reputation bonus applies, loot
    drops as usual).
- **Mage count now scales with player town count too, not just difficulty (round 8)** - see #29
  for the full spec/implementation; lives in this same `maxActiveMagesPerColor()` function.
- **"Expansion" wiki page added (round 8)** - a new `WorldStandingsScene` button (alongside #38's
  own "Reputation" button, same user message) explaining the whole capture/defense loop in plain
  language: how AI mages pick and reach targets, that Guards (#22) and an Outlook (#10) both help
  defend, the 20% "sacked" outcome even on a successful capture, and that losing the Capitol's own
  forced duel (described above) ends the game. Content cross-checked directly against this file's
  own constants (`attackerWinChance()`'s tier table, `GUARD_FIGHT_ATTACKER_BONUS`,
  `OUTLOOK_DEFENSE_BONUS`, `ATTACKER_SACKS_TOWN_CHANCE`) rather than recalled from memory.

### 8. Town Fortifications — `Removed (2026-08-12, user decision)`
- Was: upgradeable town defenses / numeric fort levels. The Armory guard system (#22) and the
  Outlook's capture-chance reduction (#10/#17) shipped what this item was really for; the
  2026-08-06 AI-vs-AI simulation research that lived here moved to #27, which still needs it.

### 9. Expanded Resources — `Done (playtest-confirmed 2026-08-14)`
- Wood and Stone added alongside Gold/Shards (`AdventurePlayer.java`, same field/signal/save
  pattern as Gold/Shards). Called **"Lumber"** in every player-facing string now (per feedback)
  - the internal field/method/save-key names (`getWood()`, `addWood()`, `onWoodChange()`, save
    key `"wood"`) deliberately weren't renamed, to avoid a save-compat migration for a display-
    only change.
- Small always-on HUD readout (`ResourceDisplayActor.java`) shows current totals right below
  Gold (zero gap, reads as one continuing column). Icons are **real art, sourced by the user
  directly from `common/maps/tileset/buildings.png`** - a resource-pile icon row already in the
  game (orange pile for Lumber, dark grey pile for Stone), cropped into a small dedicated atlas
  (`The Forsaken Realms/maps/tileset/resource_icons.png`/`.atlas`) and rendered as real
  `Image`/`TextureRegionDrawable` actors, not inline font markup - the original `[+Lumber]`/
  `[+Stone]` markup approach (mirroring how `[+Gold]`/`[+Shards]` work) turned out not to actually
  render the icon in-game (root cause not fully pinned down - see `MOD_CHANGELOG.md`), so this
  swapped to the same proven icon technique `EconomyBuildings`/`TownRestoration` already use
  instead of continuing to chase it. This same "point at coordinates in `buildings.png`" workflow
  is confirmed to work for the still-outstanding economy-building icons too (#10's Shard Mine/
  Stone Mine/Gold Mine/Exchange/Bank). Background panel uses the same `windowMain10Patch`
  stone-block frame every dialog/window in the game already uses (also applied to the Day/Clock
  widget, #6) - reads as part of the HUD's existing look rather than a separate bolted-on box.
  Still positioned in code (not `hud.json`) - forking that shared, common-to-every-plane file
  remains a full-copy-not-merge risk, same as `config.json`. **Enlarged (2026-08-05)** - icons
  were touching the panel's border; panel grew 64x32 -> 72x36 with more padding, icons now
  vertically centered in their row. **Was invisible inside towns (2026-08-05)** - it had been
  added to `GameHUD`'s `mapGroup` (grouped with the minimap it's positioned relative to), which
  gets hidden entirely on entering a town/dungeon; Gold/Shards/HP live in `hudGroup` instead,
  which only fades, never hides. Moved to `hudGroup` to match - visible everywhere now.
- Earned via Economy Buildings (#10), Random Resource Spawns (#14), combat gold variance
  (2026-08-09, below), and now a **Spawn-map walkover pickup (2026-08-10)**: the decorative
  stone tile the player starts next to has been turned into a real one-time Stone pickup
  (10-15 Stone) via a new `Reward.Type.Stone` (see below) wired through the same instant,
  no-card-flip walkover path Gold/Shards already use.
- **Combat gold variance (2026-08-09)**: winning a duel against an enemy that would have
  rewarded Gold now has a 25% chance to instead award Wood or Stone (50/50) at 50% of the gold
  amount (`EnemySprite.applyGoldVariance()`) - granted immediately with a floating status
  message, not a proper flip-card reward, since at the time Wood/Stone had no `Reward.Type` (see
  #10's Buildings entry for why extending the stock card-flip reward system wasn't worth it for
  two resources with no `items.atlas` art of their own). **`Reward.Type.Stone` now exists**
  (added 2026-08-10 for the Spawn pickup below) but this combat-variance path still bypasses it
  deliberately - no reason to route through the card-flip UI for a quiet background grant.
  **`Reward.Type.Wood` now also exists (added 2026-08-11)** - see the Dungeon Loot Variety entry
  under #10 for its first real use. Still not obtainable via shops or the `give item` console
  command.
- **Dungeon Loot Variety (2026-08-11 user request)**: opt-in `resourceLootVarietyEnabled` makes
  Cave-type dungeons' existing Shard pickups roll a 25% chance to grant Stone instead, and
  Fort-type dungeons the same 25% chance to grant Wood instead - determined from the current
  dungeon's own map path (`/cave/` vs `/fort/`), not the POI `type` field (unreliable - most Fort
  dungeons are `type: "dungeon"`, same as plenty of non-Fort ones). Deliberately implemented as a
  per-pickup code-level substitution rather than editing the underlying `manashards.tx` walkover
  objects directly - almost all cave/fort dungeons are shared `common/` map files also used by
  Shandalar and every other bundled plane, so editing them would leak a mod feature into stock
  planes. **Known limitation**: the pickup still visually looks like a shard crystal even when it
  grants Stone/Wood - a real sprite reskin would need copying the affected maps into this plane
  first (with all the internal-relative-path-rewrite risk that implies), not done this round.

### 10. Buildings (Economy Buildings) — `Done (playtest-confirmed 2026-08-13)` (2026-08-04, playtest fixes same day; Outlook + Teleporter + universal Destroy added 2026-08-09; real Outlook/Arena/Spellsmith art + animated Teleporter + Arena color diversity 2026-08-10; AI capital Armory weekly restock content fixed 2026-08-11)
- **AI capitals' Armory shops now visibly restock weekly too (2026-08-11).** The weekly-reseed
  mechanism itself (`PointOfInterestChanges.getWeeklyShopSeed()`) already fired correctly for every
  town including AI-owned capitals - the actual gap was that the 5 AI colors' Equipment/Items shop
  data (`shops.json`) was 100% fixed single-item slots with nothing for a reseed to randomize, so
  the exact same items appeared every week regardless. Converted each into a randomized pool drawn
  from that same shop's own existing item names (no new/unaudited items introduced).
- **Land shops now show a "Restocks weekly" note too (2026-08-11)**, same treatment as Armory -
  `EconomyBuildings.isLandShop()` (checks the 6 known land-shop names) added alongside the existing
  Armory check in `RewardScene`'s shared restock-note method.
- **Open question, not yet resolved:** the user separately asked to hide the Capitol's land-shop
  build option until the player has visited that color's AI capital - investigated and found the 6
  land shops are `fixedShop`+`noRestock` (unconditionally always-present, no build/repair dialog
  exists for them at all currently), which doesn't match the "can't be built" framing. Needs the
  user to clarify the actual intended mechanism before this can be built.
- **Outlook (2026-08-09):** doubles a town's fog-of-war vision radius - vision only, not the
  town's actual owned/claimable territory radius (deliberate per user spec: a scouting building,
  not a land-grab one). 100 gold, one per town, same rebuild-menu mechanism as the other 6.
- **Teleporter (2026-08-09):** fast travel between the Capitol and any town that's also built
  one. Gated in two stages: the option doesn't even appear in a town's build menu until the
  Capitol has built its own; then max 4 more across all towns (5 total). From a town, the only
  destination is the Capitol; from the Capitol, every linked town is offered. Travel drops the
  player on the overworld near the destination (not straight inside it) - same fade-transition
  mechanism as the existing defeat-respawn/debug-teleport code, just without their "enter the
  building automatically" step. 100 gold, one per town/Capitol.
- **Destroy building (2026-08-09):** every buildable/rebuildable building now offers a "Destroy"
  option - no resources refunded, reverts to the broken-shop rubble art, free to rebuild as
  something else afterward. Excluded (can't be destroyed): Arena, Inn, Armory, Land Shops (the
  Capitol's 6 fixed shops), Job Board, Spellsmith. Plain Card Shops and Booster shops previously
  had no interaction dialog at all (straight into the card-browsing screen) - they now show a
  small Enter Shop / Destroy / Leave gate first so Destroy has somewhere to live; Armory and Land
  Shops keep the old direct-entry behavior unchanged since they're excluded anyway.
- **Build menu nested into submenus (2026-08-09):** now Card Shop / Industry (4 mines) /
  Financial (Capitol-only: Bank, Exchange) / Utility (Outlook, Teleporter once unlocked) / Not
  now - was a flatter Card Shop/Bank/Exchange/Industry-submenu (Capitol) or Card Shop/4-mines-flat
  (towns) page; nested once Outlook/Teleporter pushed the option count too high for one screen.
- **Real art wired (2026-08-09 playtest round):** Outlook, Teleporter, and Arena now use the
  user's referenced buildings.png tiles (Look-out 355, Teleporter 528, Arena 227 - extracted 2x
  to 32x32 into the mod-local `new_buildings.atlas`, Archaeologist/ScienceLab packed too for
  later). Spellsmith still shows the generic SpecialShop icon - no dedicated art picked yet.
- **Real Outlook/Arena/Spellsmith art, corrected (2026-08-10):** the 2026-08-09 pass above turned
  out wrong on visual inspection during playtest (a torch bracket for "Arena", unconfirmed art for
  Outlook) - the user's referenced tile IDs were each part of a larger multi-tile composite, not a
  single tile: Outlook is 2 vertically-stacked tiles (ids 327+355, 16x32 total - the user noted
  it's 16x32, not 32x32), Arena a 2x2 block (ids 198/199/226/227, 32x32 - a colosseum entrance with
  colored gems). Spellsmith re-cropped too (16x16 smithy stall upscaled 2x, id-verified via the
  user's coordinates). `new_buildings.atlas`/`.png` rebuilt from these verified crops; old
  Teleporter/Archaeologist/ScienceLab regions dropped (Teleporter no longer uses this atlas, see
  next bullet). Non-square Outlook renders correctly with no code changes - the icon-drawing code
  already sized itself off the region's own dimensions, not a hardcoded 32x32.
- **Teleporter now uses the stock portal animation, not custom art (2026-08-10):** replaced the
  static Teleporter icon with the game's existing `sprites/portal4.atlas` (`portals.png`) - the
  same animation dungeon portals already use elsewhere. Shows the "Closed" frame until a second
  teleporter exists anywhere in the network (Capitol + towns combined), then switches to the
  looping "Active" animation (the atlas's last row, per the user's reference) - both states
  computed live off `EconomyBuildings.isTeleporterNetworkActive()`, not baked into the save.
- **Capitol Arena enemy pool diversified (2026-08-10):** was ~30 entries, all White. Replaced with
  a 34-entry pool spanning all 5 colors plus colorless/artifact flavor (adept/apprentice/master
  wizards of each color plus 4-5 color-flavored creature types, e.g. Griffin/Merfolk/Zombie/
  Goblin/Bear, plus Construct/Golem/Elemental/Sliver/Juggernaut/Gargoyle) - same enemy pool bosses
  are drawn from otherwise (none included, per the existing exclusion), user's pick to keep parity
  with the rest of the roster rather than hand-picking a smaller curated set.
- Wasteland shops (#2) can now be rebuilt as one of 6 special buildings instead of a plain Card
  Shop: Shard Mine, Gold Mine, Lumber Mill, Stone Mine, Bank, Exchange - offered via a submenu on
  the existing rebuild-shop dialog (top level: Card Shop / Bank / Exchange / Industry / Not now;
  Industry opens a second menu for the 4 producing types). **One of each of the 6 types per
  town** (a Bank AND a Gold Mine AND an Exchange etc. can coexist - just not two Banks; Card Shop
  rebuilds stay unlimited). All cost 100 gold, same as a plain rebuild.
- **Building icons** draw at their real 32x32 native size centered on the shop's 16x16 footprint
  (was incorrectly downscaled to footprint size, see `MOD_CHANGELOG.md`) - same fix applied to
  the broken-shop rubble art.
- **All 6 building icons now use real, correct art (2026-08-05)** - `economy_buildings.png` was
  originally hand-cropped from the wrong spots in `common/maps/tileset/buildings.png` (mismatched
  art, wrong size). Replaced with 6 proper 32x32 icons the user located precisely via Tiled's own
  tile inspector (Gold Mine, Shard Mine, Stone Mine, Lumber Mill, Bank, Exchange all present as
  real multi-tile building sprites in that sheet already) - see `MOD_CHANGELOG.md` for the exact
  coordinates and a gotcha worth knowing about if this comes up again.
- **Signs re-appear live on rebuild, and are hidden (not wrong) on economy buildings:** the
  sign-post hinting what a shop sells is hidden while that shop is still rubble and now reappears
  the instant it's rebuilt, no need to leave/re-enter the town (`MapStage.java` - see
  `MOD_CHANGELOG.md` for the live-`act()` visibility fix). The sign is keyed to the shop's
  original randomly-rolled type though (e.g. a Card Shop sign), so once it becomes a Bank/Mine/
  Exchange the sign would show wrong info - hidden entirely in that case for now. **Wishlist:**
  dedicated sign art per economy building type, so e.g. a Bank gets its own sign instead of none.
- **Rebuilt/destroyed shops showed the old image behind the ruin/building art - took four
  attempts across 2026-08-05 to actually fix, done now.** Attempt 1 assumed the shop's normal-
  looking body was a Tiled-rendered tile-object and tried toggling `MapObject.setVisible()` live -
  a no-op, since this codebase's renderer never actually draws gid-having objects at all. Attempt
  2 found the real mechanism (baked into the town's `Walls`/`Overlay` tile layers, not the shop
  object) and tried covering it with a precisely-offset overlay - didn't hold up in testing.
  Attempt 3 switched to hiding the real tiles instead of covering them
  (`MapStage.findOverheadTiles()`/`setShopOverheadTilesHidden()`) - the right *approach*, but
  still had a bug: the search started one row too late (`dr=1`), skipping the row that actually
  had the Walls tile (`dr=0`), found by decompiling libGDX's own tile/object-loading bytecode
  instead of continuing to reason about it in the abstract. **Fixed for real (attempt 4):** search
  starts at `dr=0`. See `MOD_CHANGELOG.md` for the full derivation, including the specific lesson
  (concrete numbers through gdx's actual formulas beat more abstract direction-reasoning, which
  had already produced two confident-but-wrong answers in a row).
  - **Made moot for Wasteland/Neutral towns specifically, same day:** the user authored
    `waste_town_player.tmx` directly in Tiled - same layout, shop buildings' `Walls`/`Overlay` art
    actually erased - and it's now what "Waste Town Generic/Identity/Tribal" all load, via a new
    plane-specific `points_of_interest.json` override (scoped to this plane only, same pattern as
    `config.json` - every other plane still reads the original common file, untouched). With
    nothing baked in to hide, the runtime hide/cover code above is now mostly a no-op for these
    towns, kept only as the right fallback for any future template that still has baked art.
- **Build menu now always shows all options, cost included, grayed out if unaffordable** -
  matches the pattern the Bank/Exchange dialogs already used (`addButtonRow`'s `enabled` flag);
  previously an option was hidden entirely if the player was short on gold, via a `hasGold`
  dialog condition. "Already have one of this type in town" is still a hard hide, not a grey-out
  - that's a structural exclusion, not an affordability one.
- **Mines/Lumber Mill:** produce +5 of their resource (Shards/Gold/Wood/Stone respectively)
  once per elapsed in-game day (`EconomyBuildings.processDaysPassed()`, hooked into
  `WorldStage.onActing()` off the same day counter #6's clock drives - so this also requires
  `dayNightCycleEnabled`). Visiting one shows a small info readout, no further interaction.
- **Bank:** shows both the player's carried gold and the town's deposited/banked total (was
  showing only carried gold - fixed same day). Deposit/withdraw in a single 100-gold denomination
  plus "Deposit All"/"Withdraw All" (simplified from 10/50/100). Balance earns 5% compound
  interest every 7 in-game days, tracked per-town (`PointOfInterestChanges.bankBalance`),
  separate from the player's carried gold.
- **Exchange:** trades between Gold/Shards/Lumber/Stone. **Standardized (2026-08-05)** to one
  denomination for every resource - buy 5 for 100 gold, sell 5 back for 80 gold (80% buyback) -
  replacing the original bespoke per-resource rates. **All four resources show real icons now**
  (2026-08-05, extended from an initial Gold/Shards-only pass) - each trade row is a `TextraButton`
  with extra `Image` icon cells added onto it, rather than a single button label, since Lumber/
  Stone's icons aren't registered as font markup the way Gold/Shards' are (see #9's HUD readout
  for why that's deliberate). **Shipped a real crash the first time** - the icon rows were plain
  `Table`s rather than `TextraButton`s, which broke a hard requirement in `MapStage.showDialog()`
  (every button-table cell must be an actual `TextraButton`) and threw an exception every frame
  the dialog was open, leaving the player stuck unable to move. Found via the actual Forge log,
  not guessed. Fixed - see `MOD_CHANGELOG.md`. **Also fixed a big visual gap** after "Buy 5"/
  "Sell 5" in each row (the button's own label cell defaulted to expand/fill, shoving the icons
  off to the far right) - see `MOD_CHANGELOG.md`.
- **"Special" (non-card-selling) shops now identified and handled distinctly (2026-08-05):**
  discovered mid-session (not previously known/documented) that some shops aren't plain Card
  Shops, and converting one into a generic economy building via the normal rebuild menu doesn't
  make sense. A destroyed special shop now gets a simple repair-only dialog instead of the Bank/
  Exchange/Industry/Card Shop choice, and gets its own dedicated icon once repaired, same as a
  plain rebuilt shop now gets a `PlainShop` icon (previously invisible - see `MOD_CHANGELOG.md`).
  Two sub-types found so far, both name-pattern-matched off `ShopData.name` (no explicit category
  field exists): **Booster** (the various `*BoosterPackShop` entries - sells booster packs, keeps
  the generic "Repair Shop" label + `SpecialShop` icon) and **Armory** (`Equipment`/`*Equipment`/
  `*Items` entries - 100% item rewards, 0% cards - gets a "Repair Armory" label + dedicated
  `Armory` icon, per user feedback after they identified one in-game via Tiled). See
  `MOD_CHANGELOG.md` for exactly how each shop position's possible types are determined
  (`commonShopList`/etc on the Tiled shop object) - notably, not every "special-looking" shop
  position is guaranteed to always roll a special type; it depends on the world's random seed.
- **Deferred, needs #7 (Dynamic Territory Control) first:** if the player loses and retakes a
  town, buildings should be cheaper to rebuild, and each building type should show its own
  ruin art on recapture instead of the generic broken-shop art (no dedicated ruin art exists
  yet for any of the 6 types, nor for the Bank/Exchange fallback case). Not triggerable or
  testable until territory capture itself exists.
- Research lab (ties into #4), Fortifications (ties into #8), Roads (ties into #2), Teleporter -
  still `Not Started`, unrelated to the economy buildings above.
- **AMENDED 2026-09-01 (doc drift caught by the round-79 audit).** This item's Done entry promises
  that any rubble slot can always be rebuilt as a plain "Card Shop". Since round 71 that is
  conditional: the primary path is now the blueprint chooser (#92), and a slot can legitimately have
  NO buildable card-shop type left - the types you know read "(built)" because of one-type-per-town,
  and everything else reads "(locked)". That slot can then only become a Bank/Mine/Trader/Outlook or
  stay rubble. This is intended #92 behavior, not a break in #10, but it will read in play as "I
  cannot rebuild this shop" - so it is recorded here rather than left to be re-discovered as a bug.

### 11. Map Polish & Terrain Customization — `In Progress` (absorbed #36, 2026-08-12 - same ask)
- More visually diverse map, prettier overall; more terrain variety (new structure/doodad sets
  per biome - ties to #7's Terrain Switch-Out reskinning machinery, which would pick them up
  automatically).
- Possibly larger map size.
- Source free 16×16 pixel-art tile/sprite packs to expand variety (Forge's adventure art is
  16×16 RGBA8888 PNG, Nearest-neighbor filtering, packed via libGDX TexturePacker `.atlas`,
  maps built in Tiled). itch.io is the best hunting ground (Kenney.nl, LimeZu, Sanctumpixel,
  etc.) — check each pack's license (CC0 vs CC-BY vs no-commercial-redistribution) before use.

### 12. Random Events — `Done (2026-09-01, user decision - satisfied by the Chest event system)`
- General random world events (could tie into the Time System's periodic-event hook, #6).
- Re-raised 2026-08-11 (wishlist batch) as "Random events" - treating as the same ask, not a new
  entry, since the description matches exactly.
- **CLOSED 2026-09-01 (user decision): this is what the Chest system became.** Built across rounds
  52-56, `util/ChestEvents.java` rolls a uniform 1-of-6 random world event on every Chest pickup -
  Gold Chest, Lost Card, Dangerous Enemy, Thief Merchant, Duplicate, and Illegal Arena - plus a
  25% shop-blueprint roll ahead of it (round 71). That covers the economic, combat and
  quest-triggering event kinds this item asked about; weather/terrain events were never pursued
  and are not planned. If more event VARIETY is wanted later, extend ChestEvents rather than
  reopening this item.

### 13. Capitol City — `Done (playtest-confirmed 2026-08-18)` (2026-08-08: upgrade flow + layout swap + building migration shipped; 2026-08-09: 6 fixed land shops, Arena/Spellsmith broken-shop rubble art, Inn starts repaired, Outlook + Teleporter + universal Destroy building added (see #10) - Teleporter is the Capitol-gated building this section long speculated about; 2026-08-10: game-over-on-loss built, see #7; Armory + dedicated Booster shop made permanent fixed slots same day; 2026-08-11: Armory UI polish, see below)
- **Armory UI polish (2026-08-11, two small user requests):** the restore dialog's label changed
  from "Repair Armory" to the user's exact requested wording, "Restore Armory". The Armory's own
  shop screen now shows a small "Restocks weekly" note (new `RewardScene.armoryRestockNote()`,
  keyed off `EconomyBuildings.isArmoryShop()`) so the player knows it refreshes on a weekly reseed
  rather than via the paid restock button - applies uniformly to the player's Capitol AND all 5 AI
  capitals' Armory-equivalent shops. **Also fixed while here**: `isArmoryShop()` had silently
  stopped recognizing the player's own Capitol Armory the moment its shop list was renamed to
  `ArmoryCommon`/`Uncommon`/`Rare`/`Mythic` by the 2026-08-10 item economy round (the check only
  matched names ending in "Equipment"/"Items") - now also matches names starting with "Armory".
- **Armory and dedicated Booster shop are now permanent, reserved slots (2026-08-10) - the same
  protection the 6 land shops already had.** User report: "if you build [Armory] first in the
  Town, then upgrade it works, if you don't build it first, then a shop can take its place and you
  can't build one" - the Capitol-upgrade migration (which carries a source town's rebuilt shops
  onto Capitol slots by count, see the migration mechanism further down this section) could park
  any migrated plain shop onto the Armory/Booster slots, permanently overriding what showed there.
  Fixed with a new `noMigrate` tmx flag (distinct from the land shops' `fixedShop` - that flag also
  suppresses the icon overlay, which Armory/Booster still need) excluding both slots from the
  migration target pool going forward, plus a repair pass that strips any pin an older, already-
  affected save left behind so it self-corrects on next load. The dedicated Booster shop's actual
  odds were also fixed while here - it turned out only ~21% likely to roll booster even when
  correctly occupied (only its common tier was booster-weighted; rare/uncommon/mythic tiers had 0%
  chance) - all four rarity tiers now guarantee booster.
- Once the player owns 5 towns, they can upgrade **one** of them into their Capitol - only 1
  allowed at a time. Needs a "which 5 towns count as owned" definition, which depends on #7
  (Dynamic Territory Control) existing first - "owns a town" isn't a concept the game has yet
  outside the player's always-safe Spawn/home base.
- **Losing the Capitol ends the game - built 2026-08-10, not yet playtested, see #7's "Capitol
  defense" entry for the mechanism.** Turned out different from the original sketch here: rather
  than the ordinary capture-resolution flow (flip-or-revert) ever touching the Capitol at all, a
  mage that reaches it triggers a forced best-of-3 duel instead - lose that duel and the run ends,
  win and the mage is defeated normally. #8 (Town Fortifications) still doesn't exist, so there's
  currently no way to make that duel less likely to happen in the first place - only to win it.
- **Certain buildings only buildable in the Capitol, not any town** - user's list so far: Bank,
  Archeologist (send an expedition/exploration party out - new building, not built at all yet,
  needs its own design pass), Exchange. **Open question, needs the user's call before this is
  built:** #10's Bank/Exchange are *already implemented and shippable* as buildable in any
  Wasteland/Neutral town today, one of each per town, no Capitol concept involved. Gating them
  behind a not-yet-built Capitol system would be a real behavior change to already-working
  buildings, not just new content - needs a decision on whether that's still wanted once #7
  (and thus "5 owned towns") actually exists, or whether Bank/Exchange stay town-buildable and
  only *new* Capitol-exclusive buildings (Archeologist, etc.) get the restriction.

### 14. Random Resource Spawns — `Done (playtest-confirmed 2026-08-12)`
- Per user spec: up to **20** walk-over resource pickups on the overworld at any time (world map
  only, never in towns/dungeons), the world starting with a full 20 scattered. Each spawn has its
  own 2-10 day lifetime; expired ones are replaced by fresh random spawns on the daily tick
  (pickups also replenish then). Types/values: **Gold 5-100**, **Shards/Wood/Stone 2-10**, awarded
  directly on walk-over with a notification.
- Opt-in via new `resourceSpawnsEnabled` flag (standard pattern). State persists on `World`;
  logic in new `ResourceSpawns.java`; rendered as lightweight per-pickup actors on `WorldStage`.
  Placement avoids water/mountains/structures and keeps 3+ tiles clear of POI icons. Full
  mechanism in `MOD_CHANGELOG.md`.
- **Gold pickups sparkle for real now (2026-08-09)** - user noticed the stock Gold pickup in
  `templeofchandra.tmx` (a `common/` main-story map) looks nicer than our alpha-twinkle and asked
  to confirm/match it. Confirmed: that pickup draws real frame-by-frame art (`sprites/gold.atlas`,
  4 "Idle" frames) via `RewardSprite`/`CharacterSprite`'s normal animation system, not a coded
  fade. Our Gold-type spawns now reuse that exact same atlas/animation (`WorldStage.
  getGoldSparkleAnimation()`) instead of the twinkle. Shards/Wood/Stone/Mystery keep the twinkle -
  no equivalent multi-frame sheet exists for them.

### 15. Dungeon Rotation — `Done (playtest-confirmed 2026-08-14)`
- Generic hostile dungeons/caves appear and disappear across the map over time (hide/show in
  place via the persisted `PointOfInterest.active` flag - despawned ones return after a cooldown).
  Visible 20-60 days, hidden 10-30 (first-guess tunables). Opt-in `dungeonRotationEnabled`.
- **POI taxonomy safety rules** (from the full 264-entry survey, see `MOD_CHANGELOG.md`):
  rotation-eligible = type `dungeon`/`cave` + `Hostile` tag ONLY. Never rotated: the 5 color
  castles + Emrakul, capitals (incl. Naktamun), all towns + Spawn, every `sideboss*` type
  (Planeswalker/unique bosses), anything tagged `Story` (Temples of Chandra/Liliana), the 7
  `Quest_*` quest-line dungeons, and DEBUGZONE/Test (excluded by name regardless of tags).
  **Update (2026-08-18)**: 11 dungeon/cave POIs were found missing the `Hostile` tag entirely -
  not deliberately "friendly," just never tagged, so they silently never despawned on loss or
  clear (user caught this by testing `Oasis` specifically - lost a fight there, it stayed put,
  unlike every other dungeon tested). All 11 (`Oasis`, 7 named `CaveX1` treasure-room spots,
  `CopperhostForest`, `YuleTown`, plus `DEBUGZONE` for consistency though it's excluded by name
  either way) now carry `Hostile` and rotate/despawn normally.
- **Active-quest protection**: a live story quest's target never despawns; a live side quest's
  target gets +30 days whenever its timer comes due (user spec).
- **Loss-despawn**: losing inside a rotatable dungeon despawns it immediately; an active
  side-quest target instead grants 3 attempts, each loss warning "N attempts remaining", the third
  despawning it. Story/non-rotatable POIs keep the plain kick-out.
- Same round: the War entry bar became a real blocking dialog ("The guards... you are at War with
  <Color>!") instead of a corner notification (user request - the silent bar read as walking
  through nothing).
- **Pool rotation (user redesign, 2026-08-08)**: world-gen overprovisions rotatable dungeons/caves
  5x, keeps 1/5 visible, and a despawn activates a RESERVE location elsewhere instead of the same
  spot returning - dungeons genuinely move. Loss-despawn hook moved to the real match-loss handler
  (the old exitDungeon hook never fired for concedes/ordinary losses). NEW-WORLD-ONLY for the 5x
  pool; old saves rotate within their existing instances.
- **Content-variety research (2026-08-10), implemented (2026-08-11).** Full audit of what
  non-quest filler dungeon/cave content could be added to the pool, both from this plane's own
  unwired POI entries and from the other bundled Adventure planes - see `DUNGEON_POOL_RESEARCH.md`
  for the original per-entry inventory. **All 28 candidates are now live**: 16 free entries (this
  plane's own already-defined-but-unplaced POIs, including `Valor's Reach Arena`, mod-specific art
  nobody had turned on) wired into their matching biome files; 4 imported from Innistrad
  (`inn_Cave_river`/`inn_dark_forest`/`inn_forgotten_lodge_1`/`inn_lodge_1`, new `maps/map/
  hunting_lodge/` folder) and 8 from Shandalar Old Border (`DemonsBargain`/`AncientDiamondMine`/
  `RiddlesLair`/5x `DragonsLair<Color>`, new `maps/map/lair/` folder) - both imports copy assets
  into this plane's own folders, common's and the source planes' own files untouched. Pool now has
  229 `cave`/`dungeon` POI entries (up from 217). Full implementation detail, including 2 real
  pre-existing data bugs found and fixed along the way (a broken sprite path, three `questTags`
  arrays with literal `null` junk in them) and a correction to the research doc's own "17 free"
  count (a text-matching false positive from JSON-escaped apostrophes), in `MOD_CHANGELOG.md`.
  **Not yet playtested** - first real test of importing Innistrad content specifically.

### 16. Side-Quest Timers - `Done (playtest-confirmed 2026-08-12)`
- Every non-story quest fails 30 in-game days after acceptance (notification on failure); the
  quest log shows "(N days left)" per quest. Opt-in `sideQuestTimerEnabled`. Accepted days persist
  on World keyed by quest id (not on AdventureQuestData - serialization compat, see
  `MOD_CHANGELOG.md`). Timer starts at the first daily tick after accepting (<=1 day slack).
- **Reference art provided by the user (2026-08-06), for whenever this gets built:**
  - **Player Capitol castle icon** - a distinct gray/white stone castle sprite (twin corner
    towers, arched entrance, red-roofed spires), meant to represent the player's own Capitol on
    the overworld map once #13 exists - visually its own thing, not a recolor of the 5 AI castle
    icons. **Saved into the repo (2026-08-07)** as `The Forsaken Realms/maps/tileset/
    Player_Capitol.png` (128x128, single image, confirmed the intended art with the user). Not
    yet wired to anything - no `.atlas` yet, and note the size: existing POI icons are 16x16
    (normal towns) to 48x48 (broken-town variants), so using this on the overworld will need a
    scale-down or atlas-region decision when #13 actually gets built.
  - **Five building-icon tile references**, screenshotted from a Tiled tileset's Properties panel
    (ID + pixel `Rectangle` X/Y, all 16×16, "Custom Properties" preview thumbnail, all sharing a
    blue palette suggesting one shared source sheet) - **source tileset file not identified**, the
    screenshot only showed Tiled's panel and small previews, not the underlying image, so these
    coordinates aren't actionable yet without that file:
    - **Look-out** (ID 355, x304 y192) - likely this item's own "Outlook" above (visible-radius
      building), same name in spirit.
    - **Archaeologist** (ID 751, x368 y416) - matches "Archeologist" above (expedition/exploration
      building) directly.
    - **Teleporter** (ID 528, x384 y288) - matches "Teleporter" above (Capitol-exclusive fast
      travel) directly.
    - **Arena** (ID 227, x48 y128) - new, not previously listed; purpose/effect not yet described.
    - **Science Lab** (ID 805, x336 y448) - new, not previously listed; purpose/effect not yet
      described.
- **Other Capitol-flavored buildings to consider** (none started):
  - **Teleporter** - **Built (2026-08-09), not yet playtested** - see #10. Unlocks once the
    Capitol has built its own, then up to 4 more towns; placeholder icon art, real Tiled-picked
    art still open (the "Teleporter (ID 528, x384 y288)" reference above is from an unidentified
    source file - not directly usable, still needs its own real pick).
  - **Barracks** - hire a garrison that patrols around the city and fights off incoming threats.
    Ties into #7's attack-unit mechanic (something for the garrison to intercept). Same idea as
    the "hireable AI guard mages" entry under #8 (Town Fortifications) - see that entry for
    duel-resolution research and a stat gotcha found while looking into it; needs a decision on
    Capitol-only vs. any-town before either gets built.
  - **Upgrade to Fortification** - likely the same system as #8, not a separate one; worth
    merging into that item's design rather than tracking twice once #8 gets scoped.
  - **Outlook** - **Built (2026-08-09), not yet playtested** - see #10. Doubles the town's
    fog-of-war vision radius, implemented as a boost to the existing vision-radius mechanic per
    the plan noted here; placeholder icon art, real Tiled-picked art still open.

## Backlog: Ideas Borrowed From Other Planes

Not commitments, just candidates worth remembering — surfaced by comparing the other bundled
Forge planes (`Realm of Legends`, `Shandalar Old Border`, `Innistrad`, `Crystal_Kingdoms`,
`Amonkhet`) against the `Shandalar`+`common` baseline our mod inherits. Each already exists as
working, shippable content elsewhere in this same repo — "borrowing" means adapting the
pattern/assets, not literal copy-paste, unless noted otherwise.

- **Duel background skins** (from `Shandalar Old Border`, `skin/adv_bg_*.jpg`) — 12 themed
  duel-screen backdrops (castle, cave, forest, island, mountain, plains, swamp, waste, etc).
  Cheapest possible visual upgrade: just image files, no mechanical changes, could literally be
  copied in as-is regardless of theme direction.
- **Terrain reskin technique** (from `Amonkhet`) — smallest/cleanest example of overriding just
  `world/tilesets/autotiles.png` + `terrain.atlas` to give the whole overworld a different
  palette without touching decks/maps/mechanics. Worth reading as a how-to if we ever want The
  Forsaken Realms to have its own terrain look distinct from Shandalar's, without a big content
  investment.
- **Commander-style boss-deck library** (from `Realm of Legends`) — 887 decks under
  `decks/legends/`, one per MTG legendary creature, used as unique named encounters instead of
  generic enemy decks (`"chaosDeckFormat": "Commander"`, `"minDeckSize": 98` in their config).
  Ties naturally into #5 Distance-Scaled AI - unique legendary bosses could replace/supplement
  generic stronger-near-the-Castle enemies.
- **Biome-organized dungeon library** (from `Realm of Legends`) — 184 maps across 8 categories
  (cave, fort, grove, magetower, merfolkpool, towns, barbariancamp, evilgrove), flavor-named
  after real MTG locations. A clean organizational template even if we build our own maps.
- **Elder-dragon-cave / end-palace map template** (from `Shandalar Old Border`) — named
  late-game dungeon pattern (`cave_nicol_bolas`, color-coded `end_palace` finales). Good
  reference for what a "capstone" dungeon per color could look like.
- **Region-per-biome narrative playbook** (from `Innistrad`, the deepest/most complete example
  in the repo) — 6 custom biomes matching real sub-regions, fully re-themed UI screens (market/
  tavern/spellsmith/reward, not just terrain), custom structure sprites, and a genuine hand-built
  planeswalker-driven questline through named locations (`main_story/approaches/
  davriels_mansion*.tmx`). This is the playbook to study if we want The Forsaken Realms to feel
  like its own place with real geography/lore (Faerûn regions, e.g. Waterdeep/Baldur's Gate/
  Neverwinter-flavored biomes) rather than a reskinned Shandalar. Also has custom booster
  contents (`printsheets.txt`/`boosters-special.txt`) as a smaller sub-idea.
- **Flavor-themed starter deck naming** (from `Crystal_Kingdoms`) — purely cosmetic idea, no
  content to borrow directly (their reference is Final Fantasy, not relevant to us), but the
  pattern - starter decks named after setting-appropriate characters/classes instead of generic
  colors - is a cheap flavor touch worth doing whenever starter decks get revisited.

## Done

- Fog-of-war groundwork (see #3 — in progress, not fully done yet)
- Earlier tweak: sacrifice condition adjustment on Misty Mountains card (unrelated one-off,
  predates this scope list)
- Borrowed `Realm of Legends`' expanded item pool (526 items total vs. common's 220 - ~306 new)
  into `The Forsaken Realms`. Pure data/asset copy, no new art or code - see `MOD_CHANGELOG.md`.
  Items are loadable/obtainable via the `give item <name>` cheat console command now; wiring
  them into actual shop inventories or reward tables is a separate follow-up, not done yet.

### 17. Territory Effects — `Done (playtest-confirmed 2026-08-18)` (idea logged 2026-08-08; first effect shipped 2026-08-09; movement-speed effect built 2026-08-15, see #63)
- **SHIPPED (2026-08-09): town-count life bonus** - +1 max life per 5 owned towns, +1 more for
  the Capitol, recomputed on restore/upgrade/capture-loss and at load
  (`TownRestoration.updateTownLifeBonus()` / `AdventurePlayer.applyTownLifeBonus()`).
- **SHIPPED (2026-08-15, see #63): movement speed effect**, the "friendlier own land, harsher
  hostile land" candidate below - player's own territory is +15% move speed always; AI-color
  territory scales with `ColorReputation` status with that specific color (Partner +10%, Happy
  +5%, Unhappy -5%, War -10%, Neutral no effect), all 5 percentages tunable via `tuning.json`. Not
  yet playtested.
- **Still open, none of these committed**: enemy spawn rate/difficulty lower on own land, higher in
  hostile territory; regeneration/healing only on friendly ground; resource pickups richer on own
  land; vision penalties in hostile land; toll/ambush risk crossing War-tier borders (extends the
  existing entry-bar/toll mechanics from towns out to the terrain itself) - the movement-speed
  effect above was the first of this candidate list actually built, not the whole item.
- Depends on: #7 (territory ownership per tile - exists), #1 (reputation tiers - exists),
  #13 (player territory - exists). This item is the payoff layer on top of all three.

### 18. Item Economy — `Done (playtest-confirmed 2026-08-14)`
Started from a full item-catalog audit (spreadsheet export, user annotated it) covering
obtainability, a proposed Common/Uncommon/Rare/Mythic tier system, and a weekly-refresh Armory
concept. Built out over one long round:
- **Catalog cleanup**: removed all items whose own description read "This item has been removed/
  discontinued", all Commander-specific items (not used in this mod), and ~15 unfixable quest
  items (see next bullet) - 76 items total (664 -> 588), full list in `MOD_CHANGELOG.md`.
- **Quest-item obtainability audit + fixes**: every quest item traced to confirm it's actually
  reachable in-game, not just present in `items.json`. Imported 17 dungeon files (+ 2 sprite
  atlases) from the bundled "Realm of Legends" plane to fix items with no working source (verified
  tileset-safe, no path collisions, before copying - same standard as every other cross-plane
  borrow this mod has done). A handful of quest items judged not worth a dedicated new dungeon for
  were removed instead, with the tradeoff noted in `MOD_CHANGELOG.md`.
- **Rarity field**: every item now carries `rarity` (Common/Uncommon/Rare/Mythic - **"Mythic," not
  "Legendary,"** matching MTG's own naming, per explicit standing instruction), usable for shop
  gating and weighted drop tables.
- **Land-art shops**: the ~60 "Landscape Sketchbook" items (grant alternate land art in the
  deckbuilder) route through the Capitol's existing land shops, which already refresh weekly via
  the pre-existing `landSketchbookShop` reward type.
- **Weekly-refreshing Armory shops** (new `PointOfInterestChanges.getWeeklyShopSeed()` - shops
  flagged `noRestock` now auto-reseed every 7 in-game days instead of never): player-town Armories
  sell a random rotation of Obtainable-Common items; the Capitol Armory sells all 4 tiers, weighted
  30% Common / 60% Uncommon / 8% Rare / 2% Mythic per week (`MapStage.java` gained optional
  per-shop `uncommonThreshold`/`rareThreshold`/`mythicThreshold` TMX overrides for this).
- **Manual "Re-roll" button - built (2026-08-11, round 7), user spec.** A new `RewardScene`
  button, Armory-only (any level), forces an immediate inventory re-roll for 100 shards base
  (difficulty-scaled like every other cost this session, `EconomyBuildings.scaledCost()`), gated to
  once per week via a NEW, deliberately SEPARATE cooldown clock
  (`PointOfInterestChanges.canManuallyRerollShop()`/`manuallyRerollShop()`) - explicit user
  requirement that this stay independent of the automatic weekly refresh above, so a manual reroll
  doesn't reset or interact with that timer at all. Reuses `generateNewShopSeed()` under the hood
  (same mechanism both the automatic weekly refresh and the ordinary paid-restock button already
  use), just triggered by the button instead of the calendar or `shopActor.canRestock()` (which
  Armory always fails, being a `noRestock` shop - the whole reason this needed its own path).
- **Three real bugs found and fixed, round 8, all from direct user observation:**
  - **Slot count was inconsistent by location, not by design** - "the Armory in the Town had 10
    slots, and the one in the Capitol had 6." Root cause: the Town Armory was still wired to a
    completely separate, older shop definition (`"Equipment"` - 4 guaranteed unique items + 6
    random) left over from before the Capitol's own 4-tier `ArmoryCommon/Uncommon/Rare/Mythic`
    system existed, never migrated. Unified to a real level-based rule instead ("Lvl 1 has 6 and
    level 2 has 8. Regardless of where they are"): `MapStage.java`'s shop-list resolution now
    appends an `L2` suffix to whichever Armory shop name got picked once that Armory reaches
    Level 2, redirecting to a new matching `shops.json` entry with the same item pool at a higher
    `count` (`Equipment`/`EquipmentL2` 6/8 total including the 4 guaranteed items;
    `ArmoryCommonL2`/`UncommonL2`/`RareL2` 8; `ArmoryMythicL2` also 8, though its 2-item pool
    means it can never actually show more than 2 - see the dedup fix below for why that's a
    graceful cap, not a bug).
  - **Duplicate items could appear in the same shop's inventory** - "There should never be 2 of
    the same item for sale," visibly true in a screenshot (two identical Landscape Sketchbooks in
    one Armory roll). `RewardData.generate()`'s `"item"` case picked `count` times independently
    at random from the pool with no exclusion tracking - now shuffles a copy of the pool once and
    takes the front, still deterministic under the same seed (so a shop's stock stays stable
    across visits) but never repeats a name within one roll, and gracefully caps at the pool's own
    size if asked for more than it can uniquely provide (the `ArmoryMythicL2` case above).
  - **A single stray "Landscape Sketchbook - Ixalan" was duplicated into both the `Equipment` and
    `ArmoryCommon` item pools** - redundant, since Sketchbooks are already properly generated for
    all 5 colors via the dedicated `landSketchbookShop` reward type on the Capitol's own 5 colored
    land shops (plus a 6th generic "Land" shop) - confirmed those are ALREADY `noRestock` (weekly
    refresh) and already show the "Inventory will refresh weekly" caption via
    `armoryRestockNote()`'s existing `isLandShop()` check, both from an earlier round - nothing
    else needed there. Removed the stray entry from both Armory pools.
- **Arena prize pools rebuilt**: the non-quest, non-obtainable item pool (447 items) was split
  across the 5 AI Capitals (Mythic excluded, ~85 items/color, rarity-balanced) added as a 50%-
  weighted bonus layer on top of each arena's existing prizes. The player's own Capitol Arena got
  the union of the 5 AI arenas' original prizes (all 5 colors, not just white) plus the *entire*
  447-item non-obtainable pool at the same 30/60/8/2 weighting. (Weighting is approximated via
  independent per-entry fire probabilities, not true mutual exclusivity - the engine's `RewardData`
  has no built-in weighted-choice primitive; flagged as a pragmatic tradeoff, not a hidden one.)
- **Boss drops**: 12 existing bosses that had zero item reward (Dark Enchanter, Emrakul, Kozilek,
  Ancient Silver Dragon, Guardian Angel, Myr Superion, Sliver Queen, Sorin, The Hydra of Shandalaar,
  Torturer, Valyx Feaster of Torment, Wounded Sliver - all confirmed actually reachable in this
  plane, unlike 5 other candidates that turned out to be orphaned/debug-only enemy data everywhere,
  not just here) now guarantee one random item from the non-obtainable Mythic pool (21 items) on
  top of their existing card/gold/life rewards. Considered importing "Shandalar Old Border"'s own
  separate bestiary for more boss targets - skipped: the arena pools above already make all 21
  Mythic items obtainable on their own, so the ROI didn't justify importing an entire second
  plane's dungeon set untested.
- **Two real pre-existing bugs found and fixed while auditing obtainability**: the Eldrazi Prison
  treasure reward referenced `"Eldrazi Rune"` (capital R) while the item is named `"Eldrazi rune"`
  (lowercase) - silently never granted anything. And the "Quick Travel Mart" (OmenStones) shop -
  sells 8 teleport-stone quest items - existed in the source dungeon (`Omenport.tmx`, object
  already wired to `commonShopList="OmenStones"`, dialogue already references it) but the shop's
  own data entry was never copied into this plane's `shops.json`, so the shop was silently empty.
  Both fixed directly.
- **Six broken cross-plane dungeon exits found and fixed** (latent crash risk, not yet hit by
  playtesting): several dungeons imported from Realm of Legends had internal `teleport` doors
  still pointing at `../Realm of Legends/...` paths for deeper levels that were never copied over
  (Eldrazi Prison's 5 Eldrazi-titan boss chambers + a "Hall of the Unifier", Tarnation's level 2,
  Church of Valgavoth's level 2, Wizard Palace's level 2) - walking into one would have tried to
  load a file that doesn't exist on disk. Disabled those doors (empty `teleport` = exits the
  dungeon instead, same as other intentionally-unbuilt exits already in these files) rather than
  importing 7 more untested dungeon files for a bonus task. One pair (Gitrog Bog levels 1↔2) had
  both ends already present in this plane - repointed to `../The Forsaken Realms/...` instead of
  disabling, since that one actually works once fixed. The Eldrazi Prison hub in particular is a
  real 7-branch boss dungeon only 1/8 built out here - worth a dedicated import pass later if
  wanted, not done this round.
- **Result**: final obtainability re-audit (scanning `shops.json` + `quests.json` + every
  `.tmx`/`.tx` map file + `enemies.json` for each item's name) shows all 588 items in this plane's
  catalog are now obtainable through some in-game path - the original audit's working hypothesis
  ("everything left non-obtainable is non-quest") held, and this round's shop/arena/boss work
  closed the non-quest gap too.
- **Starting Challenge Coins removed from Armory pools (2026-08-11 user request)**: "Challenge
  Coin"/"Silver Challenge Coin"/"Bronze Challenge Coin" (the 3 items every player starts with)
  don't make sense as something to buy - removed from the 2 `shops.json` pools that had them
  (the generic player-town "Equipment" shop, "ArmoryCommon"). No other Armory-family pool had them.
- **AMENDED 2026-09-01 (doc drift caught by the round-79 audit).** The flat item-rarity weighting
  described in this item is no longer what the Armory rolls. Round 75's #94 week/venue table
  overrides it: **week 1 has `rare: 0, mythic: 0` at every venue**, so no amount of re-rolling
  (#33) can produce a Rare before in-game day 8, and Mythics need week 3 and the Capitol. The flat
  table survives only as the fallback when the venue is unstamped. Any verification of #18, #33 or
  #51 run in the first in-game week will observe odds that contradict the text above - that is #94
  working, not #18 broken.

### 19. Roaming-Enemy Bestiary + Mage Difficulty Tiers — `Done (playtest-confirmed 2026-08-18)`
User-driven: player territory's roaming spawns felt dead once Wasteland is fully replaced by
player-owned land, the bundled non-Shandalar planes have a huge unused bestiary, and there was no
real difficulty/tier system to gate any of it by. All three tackled together since they turned out
to depend on each other.

- **Player territory had zero roaming spawns** - `player.json`'s `enemies` array was literally
  `[]`. Given a real (`WorldStage.handleMonsterSpawn()` pulls live from whichever biome currently
  owns the player's tile), this silently meant nothing ever spawned there. Fixed with a real
  61-enemy list: Wasteland/Colorless's own 49 (a genuine mixed-color roster already - wizards of
  all 5 colors, golems, animals, bandits) plus 12 more truly-colorless creatures pulled from the
  wider merged roster that weren't already in that list.
- **Full cross-plane bestiary import** (user: "let's import them all, take your time and check for
  any issues"): diffed every bundled plane's `enemies.json` against `common`'s 464-enemy baseline
  for new, color-tagged entries - Innistrad (23), Realm of Legends (870), Shandalar Old Border
  (118) - 1,011 candidates. Scoped the real asset cost before copying anything: sprite art needed
  **zero new files** (every referenced atlas already exists in `common`, confirmed for all 3
  planes), so the real cost was ~1,000 `.dck` deck files (each plane keeps its own `decks/`
  folder), now copied into this plane's own new `decks/` tree.
  - **Issues found and handled during the import** (not silently ignored): 6 enemies excluded
    (Realm of Legends' "Borborygmos and Fblthp" + 4 "Fblthp, Lost in the..." variants + "Haktos" -
    their deck files don't exist anywhere, no confident substitute); 1 deck path corrected
    (Perrie's typo'd `perrie.dck` -> the real `perrie_the_pulverizer.dck`); 2 sprite paths
    corrected (Innistrad's Watcher in the Web/Immerwolf were missing a subfolder segment - the real
    art exists in `common`); 8 enemies renamed for cross-plane name collisions (7 Realm of
    Legends/Shandalar Old Border pairs - same MTG legend represented two different ways, kept
    Realm of Legends' plain name since it's the big generic pool, suffixed the other `(Boss)`/
    `(Elite)` matching its own source-plane deck folder; 1 Innistrad/Realm of Legends pair on "The
    Gitrog Monster," suffixed Innistrad's `(Innistrad)`). Net: 1,005 new enemies, 1,469 total.
  - Considered importing "Shandalar Old Border" specifically for more *bosses* too (a separate,
    earlier ask) - already covered by this same import (its 118 are included above), so no
    additional work needed there.
- **Mage difficulty/tier system** (user proposal: derive it from each mage's deck's card-rarity
  ratio, gave a rough weighting scheme to build from). Built as **Common/Uncommon/Rare/Mythic**
  (user's pick, 4 tiers - matches MTG's own rarity words and the item-tier naming from #18, and
  lines up with a ladder that was already implicit in the base roster's naming: Apprentice/Adept/
  Master/Challenger). Weighted 1/2/4/8 per Common/Uncommon/Rare/Mythic card (doubling scale so a
  single Mythic meaningfully shifts a deck's average even among many commons), averaged per deck
  **excluding basic lands** (counting land toward the ratio would just measure "how many lands does
  this deck run," not power level), bucketed at <2.0/2.0-3.0/3.0-4.5/>=4.5. A one-off CLI tool
  (`forge.lda.DeckRarityLookup`, same bootstrap as the item-economy round's `RarityLookup`, deleted
  after use) batch-resolved real card rarity for all 1,548 resolvable decks.
  - **Real finding, corrected before shipping**: initially recomputed difficulty for the *entire*
    roster uniformly, but a sanity check against the pre-existing hand-tuned Apprentice/Adept/
    Master/Challenger ladder caught a mismatch - "Challenger" decks (real official MTG precon
    product, deliberately efficient/affordable, not rare-loaded) scored low on pure card-rarity
    despite being the base game's own hardest AI tier by design. Course-corrected: preserved every
    pre-existing enemy's original hand-tuned `difficulty` exactly (453 of 464), only applying the
    new formula to enemies that never had a value at all (all 1,005 imports + 11 pre-existing
    blanks). Re-checked after the fix - the full existing ladder now maps cleanly onto the new tier
    names in order for both tested colors.
  - **Real bug found and fixed while wiring this up**: `BiomeData.getEnemy(float
    difficultyFactor)` silently discarded whatever was passed in and substituted the player's win
    rank instead - meaning difficulty gating never actually reflected anything except overall
    progression, no matter what a caller intended. Fixed to respect its parameter; callers still
    pass player rank as the base signal (unchanged feel), now genuinely usable by other systems.
- **Roaming-spawn proximity/reputation intrusion** (user: "if a colored city is in the area, that
  color might spawn... if you're at war with a color they might spawn"): new
  `TerritoryControl.findNearbyForeignColor()` finds the nearest OTHER color's town/capital/castle
  within 40 tiles; `WorldStage.handleMonsterSpawn()` rolls a 25% base chance (per spawn attempt) to
  substitute that color's biome for the current one, scaled by `ColorReputation` standing with that
  color: 0x at Partner (never intrudes), 0.5x Happy, 1x Neutral, 1.5x Unhappy, 2.5x War. A War-tier
  border is genuinely dangerous to linger near; a Partner-tier one never bleeds in at all.
- **Town-fight capture odds now tier-weighted** (user: "we could use this to determine the chances
  to win a town fight... currently always 50/50"): `TerritoryControl.onMageArrived()`'s flip-to-
  attacker-or-revert-to-neutral roll (had an explicit TODO for exactly this since the reputation
  round) now uses the attacking mage's tier - Common 10% / Uncommon 30% / Rare 70% / Mythic 90% -
  instead of a flat coin flip.
- **Content-level POI re-theme, settling the long-open question from #7** (user: "I think they
  should re-theme and any colorless/wasteland POIs should be player terrain enabled"). Dungeon
  enemies are hardcoded per-object by name in each map file, not drawn from a biome pool - so
  re-theming doesn't need duplicate map content (the "5x the content" cost #7 originally flagged
  and declined). Instead, `MapStage`'s existing named-enemy lookup now checks
  `TerritoryControl.reThemedEnemyFor()`: if a POI's *current* land owner differs from whichever
  color's biome originally placed it at world-gen, substitute a same-difficulty-ceiling pick from
  the current owner's roster - `player` included, so a captured dungeon re-themes to the player's
  own roster too. Boss and quest-tagged encounters are exempt (often logic-critical or a scripted
  fight - shouldn't silently change).
- **Post-round audit (2026-08-10, user request "is there anything we might have missed"), two real
  gaps found and mostly fixed:**
  - **The 1,005 newly-imported enemies were never actually wired into any spawn pool** - present
    in `enemies.json` with full data, but referenced by zero biome `enemies[]` roaming lists and
    zero arena `enemyPool`s. Only 121 (12%) were reachable at all, purely by coincidence (named
    inside dungeons imported earlier). Fixed: all 967 non-boss new enemies added to every color
    biome whose letter appears in their `colors` tag (the same "contains," not "starts-with," rule
    already used by the pre-existing roster - confirmed by sampling `white.json`'s existing list
    before writing this). The 38 boss-flagged Shandalar Old Border imports are deliberately left
    unwired - they're not roaming material, and building 38 new boss dungeons for them is real
    future-work scope, not a quick fix.
  - **284 of the new enemies' own item-type rewards reference 88 item names this plane doesn't
    have** - `RewardData`'s item case silently no-ops (console-logs "Missing item," doesn't crash)
    when this happens, so it wasn't caught by anything short of directly cross-referencing every
    reward against the catalog. Categorized by checking each missing name's own definition in its
    source plane: 36 are quest-flagged trophy items ("X's Trophy" / "Kill Trophy" - "give to
    Chevill for a reward" - referencing quest content this plane doesn't have) and 3 are dangling
    references that don't exist in ANY bundled plane's item catalog (one is a literal template
    placeholder, "Name of Item") - correctly left alone, matching the same judgment call already
    made for the Hexkey/Shard/Cartouche/Key/Statue-part items earlier this round. The remaining 49
    are self-contained equipment with no external dependency - imported 40 of them (all tagged
    `Rare`, a judgment-call default for boss-exclusive gear); the other 9 turned out to be
    Commander-specific (cross-checked their `startBattleWithCard` edition codes against every
    `Type=Commander` edition file) and were, independently, *already* in this round's own
    76-item-removed list - both signals agreeing is a good sign the categorization is sound. Net:
    48 item references remain intentionally unresolved (silent no-op on those specific reward
    slots only - every affected enemy has other working reward types alongside).
- **Boss drop odds corrected (2026-08-10, same-day follow-up)**: user felt a *guaranteed* Mythic
  drop from the 12 boss fix above undersold "Mythic" as a rarity word - changed to 90% Rare / 10%
  Mythic (same independent-probability-per-entry approximation used everywhere else this round;
  reused the original 86-item non-obtainable Rare pool + the same 21-item Mythic pool, both
  reverified still fully valid against the current 628-item catalog). Also checked whether any
  *other* boss has an existing multi-item random reward pool worth adding these to - answer: no.
  Every other boss-with-an-item-reward in the pre-existing roster (23 of them) gives exactly one
  fixed signature item - 5 are literally the colored "Key" quest items (Akroma→White Key, Ghalta→
  Green Key, etc.), the rest are character-named unique flavor items (Chandra's Stone, Teferi's
  Staff, Zedruu's Lantern...). Diluting those with a chance at generic loot would work against
  their own design, so left alone - the 12 already fixed were the only real multi-item pools that
  existed.
- **The 38 orphaned Shandalar Old Border bosses, resolved without a dungeon import (2026-08-10,
  same-day follow-up)**: user asked whether these had dungeons in their source plane at all. They
  do - 37 of 38 (only "Slivdrazi Monstrosity" is orphaned even in Shandalar Old Border's own data).
  Checked feasibility of importing those 37 dungeons directly: zero depend on anything outside
  `common`'s shared tileset, but 24 of the 34 unique files needed collide by filename with content
  already at that path (verified directly - `common`'s own `grove_5_foresttitan.tmx` is a
  completely different, boss-less filler dungeon that just happens to share a name with Shandalar
  Old Border's real "Elf Queen Guay" boss room), and 9 are mid-chain rooms needing their own
  preceding levels imported too (same situation as the Eldrazi Prison hub). A real, separately-
  scoped task, not a quick fix. Given the user's own bosses have a fairly even color spread (checked
  directly: 3-6 per mono color, 17 more across multicolor/5-color), asked instead: **surface them as
  extremely rare roaming encounters in their own color's territory, gated on the player being
  genuinely At War with that color** - no dungeon needed at all, since a rare boss encounter is a
  natural fit for the existing roaming-spawn system rather than requiring scripted dungeon content.
  Built as `TerritoryControl.WAR_TIER_BOSSES` (a hand-curated `color -> boss names` map, multicolor
  bosses appearing under every color they contain, same convention the roaming-pool wiring fix
  already used) + `rollWarTierBoss()` (a `WAR_TIER_BOSS_CHANCE` of 4% - "very rare," the user's own
  words, layered on top of an already-rare condition). `WorldStage.handleMonsterSpawn()` checks this
  first, once the roll's effective color (after the existing intrusion-substitution check) is
  confirmed War-tier via `ColorReputation.getStatus()`; a miss falls through to the ordinary pick,
  same as any other roll. Since "Slivdrazi Monstrosity" no longer needs a dungeon home either, all
  38 are included, not just the 37 with one. Not yet playtested.
- **Final pre-playtest audit (2026-08-10, user request "one last check")**: ran a from-scratch
  reachability pass across the whole catalog rather than trusting earlier partial checks - every
  item (628), every enemy (1,469), every quest item (63, confirmed each resolves to a real source
  and traced the dungeon-sourced ones by hand to confirm none sit behind a broken/missing path).
  Two real things found and fixed, one false alarm ruled out:
  - **11 enemies (`Graaz`, `Hope of Ghirapur`, `Karn`, `Liberator`, `Omarthis`, `Syr Ginger`, `The
    Dawning Archaic`, `The Peregrine Dynamo`, `Traxos`, `Ulamog`, `Zhulodok`) were completely
    unreachable** - tagged `colors:"C"` (a different colorless marker than the blank-string
    convention the earlier `player.json`/roaming-pool fixes checked for), so they fell through
    every wiring pass done so far. Added all 11 to both `colorless.json` and `player.json`'s
    roaming pools, same treatment the blank-color enemies already got.
  - **A JSON-escaping false alarm, ruled out rather than "fixed"**: the first obtainability pass
    flagged 14 of the 40 recently-imported trophy items as unreachable - every one of them has an
    apostrophe in its name (`Attendant's Prayerbook`, `Windwalker's Blessing`, etc.). Root cause:
    `items.json`'s own name field round-tripped through `ConvertTo-Json` at some point and stores
    a literal apostrophe, while `enemies.json`'s reference to that same name (never re-serialized
    the same way) stores the JSON-escaped `'` form - a plain substring search for one doesn't
    find the other. Confirmed by direct byte inspection, not assumption. Rebuilt the audit script
    to check both forms; all 14 resolved cleanly, and this doubles as confirmation the "None of
    these need missing dungeons" question is fully settled too, once verified independently that
    every dungeon-sourced quest item's own reward object sits on an already-reachable floor (spot-
    checked `Victor's Key` directly - its "Victor" enemy sits on `Church_of_Valgavoth_1.tmx`'s main
    floor, unrelated to that file's own already-disabled dead-end door from an earlier round) and
    that zero teleport targets anywhere in this plane point at a file that doesn't exist (verified
    against the actual runtime resolution `TileMapScene.load()` uses - `Config.getFilePath()`,
    simple prefix-concatenation from the plane root, not a path relative to the referencing file -
    confirmed against a known-working stock example, `grolnok.tmx`, before trusting the result).
  - **Final state: 0 items unobtainable, 0 enemies unspawnable, all 63 quest items resolve to a
    real, reachable source.**

### 20. Upgradable Arena — `Done (playtest-confirmed 2026-08-13)` (2026-08-11: art, upgrade trigger, Ante-off, and the Challenge Arena mode all built; playtest round 2 same day moved the upgrade/toggle UI and fixed the Level 1 art; Deck Tester mode built same day, round 3; playtest round 5 fixed the Upgrade/toggle/Deck Tester buttons rendering off-screen, and a wrong-jar deploy bug that meant round 3 never actually reached the player; round 7 moved Deck Tester next to the mode toggle per user follow-up)
- **"No ant in Arena" resolved**: means Ante (the mechanic where match winner takes a card from the
  loser's deck - "ant" was a typo missing the "e"), which is on by default for every match
  currently. **Built**: Ante is now force-disabled for Arena matches specifically (new
  `EnemyData.noAnte`, set on a per-fight clone in `ArenaScene.loadArenaData()` - the player's
  global Ante setting is untouched, still applies to every non-Arena duel).
- **Art - built, then corrected same day (playtest round 2).** Level 1 art was first composited
  into a 16x32 VERTICAL stack (buildings.png IDs 378/379) per the user's original spec, "twice,
  with emphasis" - the first actual screenshot showed this reading as an awkward double-stack, not
  one coherent building. Corrected to a plain 32x16 LANDSCAPE crop of the same two tiles (no
  compositing) per the user's follow-up correction - exactly the "straight bounding-box crop" the
  first round had deliberately avoided. Level 2 art untouched throughout.
- **Upgrade trigger - built, then moved (playtest round 2, same day).** Originally a pre-entry
  `MapStage` dialog (`EconomyBuildings.openArenaEntryDialog()`, "Enter Arena"/"Enter Challenge
  Arena"/"Upgrade to Level 2") shown on collision before ArenaScene even loaded - per user
  follow-up ("have the Upgrade be an option inside the arena interface vs. a gating menu"), that
  dialog is gone. Collision now enters `ArenaScene` directly; the screen itself now has an
  "Upgrade to Level 2 (100g)" button (level < 2) and a "Switch to Normal/Challenging Arena" toggle
  button (level >= 2, replacing the old separate "Enter Challenge Arena" pre-entry choice) - both
  hidden once a tournament run is actually in progress. Shared plumbing
  (`PointOfInterestChanges.getBuildingLevel()`/`setBuildingLevel()`, `EconomyBuildings.
  getArenaSprite(level)`, `BUILDING_UPGRADE_COST`) is the same infrastructure Armory's own upgrade
  (#22) already uses. Known cosmetic-only limitation: the overworld icon updates on next
  visit, not instantly (it's set once at map-load, not re-evaluated live).
- **Challenge Arena mode - built (2026-08-11).** Level 2's arena entry dialog now offers a second
  button, "Enter Challenge Arena", alongside the existing "Enter Arena" (which stays identical to
  Level 1 - same pool, same 3-round bracket, same 100g `entryFee`, unchanged). New `arenaChallenge`
  TMX property on `player_capital.tmx`'s Arena object (id 61), parallel to the existing `arena`
  property: 84-enemy pool (union of every `boss:true` entry, every "miniboss"-deck-path entry, and
  every Master-tier Wizard in `enemies.json`, each verified to resolve to a real `.dck` file),
  `entryFee:300` (3x Level 1's 100g), 3 rounds of escalating rewards (300/500/800 gold + Uncommon/
  Rare/Mythic-tier item pools at 25%/65%/15% probability each round, plus a guaranteed Rare card
  round 2 and a guaranteed Mythic Rare card round 3 - no Common or Uncommon cards ever drop,
  matching the user's "No Commons, Low Uncommons, High Rare, reasonable Mythic" spec). Gold/item-
  pool numbers are Claude's own proposal (not explicitly specified by the user beyond "~3x" entry
  and the rarity-skew description) - flagged here for the user to tune if the balance feels off.
  **Best-of-1 enforced**: about 30% of the Challenge pool (bosses/Planeswalkers/mini-bosses)
  default to best-of-3 (`EnemyData.gamesPerMatch=3`) in vanilla `enemies.json`; `ArenaScene.
  loadArenaData()` gained an `isChallenge` flag that forces `gamesPerMatch=1` on the per-fight
  cloned `EnemyData` (same clone-not-mutate pattern already used for `noAnte`), so every Challenge
  fight is best-of-1 regardless of that enemy's own data. The "Enter Challenge Arena" button only
  appears when `arenaChallenge` exists AND the building is Level 2 - every other arena in the game
  (the 5 AI capitals') has no `arenaChallenge` property and silently gets no button at all, so
  upgrading an AI capital's Arena (if that's ever even reachable) can't hit a missing-property crash.
- **Deck Tester - built (2026-08-11, round 3), not yet playtested.** Per spec: the player picks 2
  of their own saved decks, **pilots one themselves** (an ordinary duel from their side), AI pilots
  the other - a plain `DuelScene` match where the opponent's deck happens to be player-supplied
  instead of a canonical enemy `.dck` file. New `ArenaScene` button, visible only at Level 2 (same
  gate as the Challenge toggle, but independent of whether this arena even has a Challenge pool),
  hidden mid-run same as the other two building buttons. Two sequential picker dialogs (which deck
  YOU pilot, then which deck the AI pilots - no exclusion between them, a same-deck mirror test is
  allowed) list every non-empty saved deck slot (`AdventurePlayer.getDeckCount()`/`getDeck()`/
  `isEmptyDeck()`). Mechanism: new `EnemyData.fixedDeck` (transient `Deck` field, checked in
  `DuelScene`'s AI-deck-resolution ternary ahead of `copyPlayerDeck`/`generateDeck()`) lets the AI
  pilot an arbitrary pre-picked `Deck` object instead of anything resolved by name; the player's
  side is handled by a temporary `AdventurePlayer.setSelectedDeckSlot()` swap around the
  `initDuels()` call (restored immediately after, since `initDuels()` copies the deck synchronously
  at call time). The AI-side `EnemyData` is a clone of the stock "Doppelganger" enemy (colorless,
  already ships with `copyPlayerDeck:true` baked in as its own "mirror match" flavor - reused here
  as a low-risk shell with a guaranteed-valid sprite/avatar, then overridden with `nameOverride:
  "Deck Tester"`, `noAnte:true`, `rewards: []`). A `deckTesterMatch` guard flag in `ArenaScene`
  makes `setWinner()` (the automatic `IAfterMatch` callback `DuelScene.afterGameEnd()` fires on
  whichever scene launched the duel) skip all bracket-manipulation logic for a Deck Tester match,
  since it has no bracket/round state of its own - just resets the screen instead.
  **Repositioned (round 7)**: was its own row above the Upgrade/toggle buttons (overlapped the
  bracket-tree view per a user screenshot) - now shares a row with the mode toggle, immediately to
  its right.

### 21. Speed Up All Monsters — `Done (playtest-confirmed 2026-08-14)` (tier speed rebalance only)
- User idea, refined over two rounds into "Increase the speed of enemies by tier" - a tier-scaled
  rebalance rather than a flat blanket speed-up (tougher enemies move faster, not every roaming
  enemy uniformly). Ties into the existing `EnemyData.tier`/mage-difficulty-tier system (#19).
  Research confirmed this DOES cover Territory Control mages too (#7) - checked the actual
  movement code and mages use the exact same per-enemy `speed()` value as ordinary roaming
  enemies, just steering toward a town instead of the player; no separate "fixed pace" system
  exists (an earlier note here speculated otherwise, corrected 2026-08-13).
- **Data-only rebalance, built 2026-08-13** (`enemies.json`, no code changes). User-specified
  target windows (min-max, median), flyers biased toward the top of each:
  - Common: 5-30, median 20
  - Uncommon: 15-40, median 30
  - Rare: 25-50, median 40
  - Mythic: 35-60, median 45
  - Exception (user spec): the 6 Rare/Mythic enemies that were sitting at speed 1 (Ghalta,
    Lathliss, Sliver Queen, Akroma, Griselbrand, Lorthos - all big, iconic, deliberately-slow
    finishers) were hardcoded to 10 instead of being pulled into the general rescale, so they stay
    notably slower than their tier without literally being "stationary."
  - **Method**: a two-segment (piecewise) linear rescale per tier - old-min to old-median mapped
    onto new-min to new-median, old-median to old-max mapped onto new-median to new-max - computed
    against each tier's CURRENT min/median/max (re-measured fresh at build time, excluding the 6
    exceptions from that baseline so their speed=1 floor didn't distort the low end for everyone
    else). This is the only method that can hit an exact target min/max/median simultaneously
    while still preserving each enemy's relative speed ranking within its tier - a plain single
    linear min-max rescale can't (checked: Common's old median sat at 25% of its old range, but
    25% of the new 5-30 range is only ~11, not the target 20).
  - **Flyer bias**: after the base rescale, each flying enemy's speed is blended 35% of the way
    toward its tier's new max (`newSpeed = base + (tierMax - base) * 0.35`) - Claude's own
    proposed mechanism/fraction, not user-specified beyond "flyers on the higher end." Verified
    flyer averages land above non-flyer averages in every tier except Mythic, where 2 of the 6
    speed=1 exceptions (Akroma, Griselbrand) are themselves flying and get force-set to 10
    regardless of the blend - an expected, correct side effect of the exception rule taking
    priority over the general flyer bias for those two specific enemies.
  - **6 enemies deliberately left untouched**: Evil Wall, Greater Sandwurm, Wandering Treefolk,
    Wounded Sliver, Karona (Boss), Bazaar Keeper have no `speed` field in the data at all (found
    during the analysis, not something the rescale should paper over) - likely intentionally
    stationary/scripted encounters, so no field was added.
  - **Verified post-write** (re-read the file fresh from disk, independent of the in-memory
    values used to compute it): Common min=5/median=20/max=30, Uncommon min=15/median=30/max=40,
    Rare min=10/median=40/max=50, Mythic min=10/median=45/max=60 - all match target exactly (the
    "10" floors on Rare/Mythic are the 6 hardcoded exceptions, as intended).
  - **Edit technique note** (for future large `enemies.json` data passes): a straightforward
    parse-modify-`ConvertTo-Json`-rewrite was deliberately avoided (reserialization risk on a
    1474-entry, 4.9MB file - same class of large-diff risk already seen once this project on
    `shops.json`). Instead used a brace-depth-tracked scan to find each enemy's exact character
    range in the raw file text, patched only the `"speed"` value within each range positionally,
    and left every other byte untouched - confirmed via `git diff`, only `"speed"` lines changed
    (1433 of 1474; the other 35 computed to their existing value and correctly produced no diff).
- Still open, deliberately not built yet (user: "hold this for now"): a separate terrain/
  reputation-based PLAYER speed modifier discussed alongside this - +15% on the player's own
  territory (the dedicated "player" biome painted around owned towns/Capitol, `BiomeData.name`,
  nothing to do with deck color), and on any other color's terrain a modifier keyed off
  `ColorReputation.getStatus()` for that color: Unhappy -5%, War -10%, Happy +5%, Partner +10%,
  Neutral unaffected. Confirmed feasible and mapped to the exact hookpoint (`WorldStage.
  handleMonsterSpawn()`'s existing road-speed check already reads the player's current `BiomeData`
  at that point) but not implemented - only this tier speed rebalance was asked for so far.

### 22. Armory Guard Hiring (Level 2 unlock) — `Done (playtest-confirmed 2026-08-13)`
Full loop is real and reachable in-game from a fresh save: Armory starts Level 1 (unchanged
behavior) -> "Upgrade Armory (100g)" button on its `RewardScene` page -> confirms, spends gold,
flips to Level 2 art -> "Manage Guards" button appears -> hire any of 4 tiers (slot-limited,
cost-gated, upfront payment) or dismiss an existing one -> weekly salary auto-deducts (disbands on
missed payment) -> an attacking mage must beat every hired guard, strongest first, no weakening
between fights, before it can proceed to the town's/Capitol's own capture resolution.
- **Tiers** reuse the plane's existing Apprentice/Adept/Master/Grandmaster ladder (`EnemyData.tier`,
  built 2026-08-10 for mage difficulty - not a new parallel system; top tier renamed from
  "Challenger" 2026-08-13 per user, display-only - internal strings stay Common/Uncommon/Rare/
  Mythic). Towns hold 1 guard, Capitols 2 (`EconomyBuildings.maxGuardsForTown()`).
- **Costs** (`EconomyBuildings.guardWeeklyGoldCost()`/`guardWeeklyShardCost()`): 50/100/150/200
  gold weekly (Apprentice/Adept/Master/Grandmaster), +5 shards only at Grandmaster - same amount
  charged upfront on hire, exact user spec.
- **Combat odds** (`TerritoryControl.guardFightAttackerWinChance()`): reuses the Item Economy
  round's own Common/Uncommon/Rare/Mythic = 1/2/4/8 power weighting, `attackerPower /
  (attackerPower + defenderPower)`. Base matrix (attacker rows, defender columns) before the
  balance pass below:

  | Attacker \ Defender | Apprentice | Adept | Master | Grandmaster |
  |---|---|---|---|---|
  | **Apprentice** | 50% | 33% | 20% | 11% |
  | **Adept** | 67% | 50% | 33% | 20% |
  | **Master** | 80% | 67% | 50% | 33% |
  | **Grandmaster** | 89% | 80% | 67% | 50% |

- **Balance pass (2026-08-11, same day, after the user saw the matrix above)**: felt too safe for
  the defender once compounded with the base town-capture roll (a Common attacker vs. a hired
  Grandmaster guard alone was ~11%, then another roll on top). Attacker gets a flat +10% in any
  guard fight (`GUARD_FIGHT_ATTACKER_BONUS`), countered by -5% if the defending town has an
  Outlook (`OUTLOOK_DEFENSE_BONUS` - Outlook's first role beyond fog-of-war vision
  radius), net +5%/+10% attacker advantage with/without one, clamped to [0,1]. The pure tier-math
  function above is unchanged - this is a modifier layered on only where a fight actually resolves.
- **Outlook extended to the base town-capture roll (2026-08-11, same-day follow-up)**: the -5%
  bonus above originally only fired inside guard fights; the user asked for it to also apply to
  the underlying `attackerWinChance(tier)` capture roll itself (guards or not). `TerritoryControl.
  townHasOutlook()` (new helper, `PointOfInterestChanges.hasEconomyBuildingOfType(OUTLOOK)`) now
  gates a -5% off `captureChance` in the player-owned-town branch of `onMageArrived()`, same
  `Math.max(0f, ...)` clamp as the guard-fight version (never risks going negative given the
  existing 10/30/70/90 baseline range). Renamed the constant from `GUARD_FIGHT_OUTLOOK_DEFENSE_
  BONUS` to `OUTLOOK_DEFENSE_BONUS` to reflect the wider scope. Only wired into the player-owned-
  town branch, not the AI-vs-AI branch - an AI-held town could theoretically still have a player-
  built Outlook standing if the player lost it after building one, but the user's ask was framed
  around defending the player's own towns, so this was left AI-vs-AI-unaffected pending explicit
  scope from the user if that matters.
- **Outlook info dialog now explains itself (2026-08-11, round 3)**: clicking a built Outlook used
  to open an empty/action-less dialog. `EconomyBuildings.refreshOutlookInfoDialog()` now shows real
  text - vision-radius multiplier worded dynamically (x2 town / x3 Capitol, matching
  `getTownVisionRadiusTiles()`'s actual behavior rather than a hardcoded guess) plus the real 5%
  capture-chance reduction named above (`OUTLOOK_DEFENSE_BONUS`).
- **"Sacked" outcome (2026-08-11, same round)**: even a successful capture isn't guaranteed to
  stick - a separate 20% roll (`ATTACKER_SACKS_TOWN_CHANCE`) can revert the town to a neutral ruin
  instead ("they won the town, but sacked it"), only ever rolled after a genuine contest (never for
  claiming truly-unclaimed land). Applied uniformly to both player-owned town defense and AI-vs-AI
  captures (a judgment call, not explicitly scoped either way by the user - flagged in
  `MOD_CHANGELOG.md` in case player-only was intended).
- **A real, corrected gap from this same day's own earlier research**: player-owned ordinary towns
  were *already* attackable (not "can't be attacked" as first reported) - `isWastelandTown()` is a
  static property of a town's original biome tag, true for player-owned wasteland-origin towns
  exactly as much as genuinely-unclaimed ones, so `onMageArrived()`'s neutral-claim branch treated
  both identically: instant unconditional flip, zero roll, zero defense. Player-owned ordinary
  towns now get their own branch (guard defense, then the same `attackerWinChance(tier)` roll the
  AI-vs-AI capture path already uses) - truly-neutral, never-claimed wasteland towns are completely
  unchanged. The Capitol's own 2 guards fight in strict sequence (strongest first, independent
  fresh roll each fight - a win against guard 1 does not weaken the attacker for guard 2), and only
  once both fall does the existing forced-duel mechanic trigger.
- **UI**: `EconomyBuildings.openManageGuardsDialog()` (built directly against a raw
  `scene2d.ui.Dialog`, not the DialogData/ActionData system - see `MOD_CHANGELOG.md` for why that
  system didn't fit); `RewardScene`'s `guardsButton`/`upgradeButton`, Armory-only, mutually
  exclusive by level. **Resized (playtest round 2, 2026-08-11)**: was one full-width button per
  tier/guard (too tall per the user's screenshot) - now two half-width buttons per row.
- **Map indicator icon - built (2026-08-11).** `guard_icons.atlas`/`.png` (composited from the 4
  already-extracted 8x8 tier PNGs, sourced from `common/maps/tileset/dungeon.png` IDs 83/84/86/88
  per the user's mockup), drawn in `PointOfInterestMapSprite.draw()` - the strongest guard's icon
  only, bottom-left corner, even at a 2-guard Capitol. **Fixed same day (playtest round 2)**: now
  draws one icon per hired guard (up to 2 at the Capitol), not just the single strongest.
  **Enlarged (2026-08-11, round 3)**: drawn at a fixed 12x12 instead of the source art's native 8x8
  (user: "a little small... let's try 12x12") - source crop unchanged, just scaled up at draw time.
- **Hire-button text overflow fixed (2026-08-11, round 5)** - user report: "the armory text is too
  big for the buttons now." The half-width Hire buttons (#48's `addHalfButton()`, 118 units wide)
  were already marginal for text like "Hire Apprentice (50 gold/week)" before #23's icon markup
  existed, and clearly overflowing/overlapping between columns after. Widened to 140, shortened
  "/week" to "/wk", and added a `[%75]` text-scale prefix to the Hire labels specifically (Dismiss
  labels are much shorter and untouched). See #23 and `MOD_CHANGELOG.md` for the full round.

### 23. Resource Icons on Building/Shop Menus — `Done (playtest-confirmed 2026-08-13)` (built 2026-08-11 round 4; wrong-jar deploy bug fixed round 5, same day)
- Original ask (2026-08-11): a gold icon next to the Bank's Deposit/Withdraw amounts
  (`EconomyBuildings.refreshBankDialog()`), plain text right now (`"Deposited: N gold"`).
- **Expanded same day, round 3**: apply the same treatment - resource icon(s) inline after every
  cost/amount - across ALL shop/building dialogs, not just the Bank: Guard hiring costs
  (`buildManageGuardsDialog()`), and every repair/construction/upgrade cost (Job Board restore,
  individual shop rebuild, `BUILDING_UPGRADE_COST` Arena/Armory upgrades, Archaeologist's 1000g,
  etc). Explicit reference point: "you did a great job on the exchange shop menu... I want all
  other shops to follow that pattern" - the Exchange dialog's existing icon+amount row layout
  (`EconomyBuildings`'s `Trade` inner class / its resource-icon `Image` + label pairing) is the
  template to extend everywhere else, not a new design.
- **Also raised in the same message, likely belongs here rather than as a separate feature**: most
  costs across the mod are currently a flat placeholder ~100g (explicitly called out by the user as
  "basically a placeholder... will be adjusted pretty soon to be a combination of gold/wood/stone/
  shards") - the icon work may end up threading through whatever multi-resource cost values that
  rework lands on, not just gold icons next to gold-only costs.
- **Difficulty price multiplier - new idea, same message, not yet scoped**: current prices assumed
  to represent Normal difficulty; scale by difficulty tier - Easy 25% cheaper, Hard 25% more,
  Insane 50% more (Normal itself unmodified, i.e. baseline 1.0x). Not yet clarified: which costs
  this covers (all of the above, or a subset), where the multiplier table should live (a new
  `EconomyBuildings` helper keyed off `AdventurePlayer`'s difficulty, parallel to the existing
  per-difficulty scaling patterns already used elsewhere - e.g. FoW vision radius tiers, per-color
  mage caps), and whether it composes with the reputation-tier price modifiers `ShopActor.
  getPriceModifier()` already applies (#1) or is a separate multiplicative layer.
- **Scoped and built same day, round 4.** Clarified with the user (AskUserQuestion) before
  building: icons roll out to every dialog now (not just Bank); the difficulty multiplier applies
  to building repair/construction/upgrade costs AND guard hiring costs, explicitly NOT to card/
  item shop prices; and it does NOT stack with reputation pricing - it only touches costs that have
  no reputation modifier today, so there's no double-penalty question to resolve.
  - **Icons**: turned out simpler than the Exchange dialog's own approach once checked - `[+Gold]`/
    `[+Shards]` are already real, working font-markup tags (`Controls.getTextraFont()`'s registered
    items.atlas icons, already used elsewhere in the codebase - `InventoryScene`, `ShardTraderScene`,
    `ItemData`, this mod's own Bank dialog title). Every cost this round is gold-only or gold+shards,
    so plain markup in the button/label text (`amount + " [+Gold]"`) was enough - no new Image-actor
    plumbing needed. (Exchange's Image-actor approach exists only because Wood/Stone have no
    font-registered icon - irrelevant here.) Rolled out to: Bank (deposit/withdraw amounts, balance
    display - not difficulty-scaled, moving your own money isn't a "cost"), Guard hiring (weekly
    gold/shard cost - both hire-upfront and the recurring salary, since both read the same
    function), Job Board restore, individual shop rebuild, Capitol upgrade, new-building
    construction cost, Arena/Armory Level 2 upgrade, Archaeologist expedition cost.
  - **Difficulty multiplier**: `EconomyBuildings.difficultyPriceMultiplier()`/`scaledCost(int)` -
    same index-lookup-against-config's-difficulties[]-array pattern already established by
    `World.visionRadiusDifficultyOffset()` (#3) and `TerritoryControl.maxActiveMagesPerColor()`
    (#7), confirmed directly (not assumed) that the plane's `config.json` defines exactly 4 tiers,
    Easy/Normal/Hard/Insane in that order - `0.75 + 0.25*index` lands exactly on the user's 4
    numbers (0.75/1.00/1.25/1.50) as a flat linear step. Wired into every cost listed above (Bank
    deposits/withdrawals and the Exchange's buy/sell rates deliberately excluded - see the user's
    own scoping answer). Two button labels (`ArenaScene.arenaUpgradeButton`, `RewardScene.
    upgradeButton` for the Armory) previously baked their cost into the label text ONCE at
    construction from the raw constant and never refreshed it - both now also re-set their text
    wherever their visibility already gets refreshed, so the displayed number can't go stale.
  - Not yet playtested - needs a real save on each of the 4 difficulty tiers to confirm the numbers
    actually differ in-game (visual verification wasn't possible from this session - no way to run
    the libGDX desktop client directly, only compile/deploy).

### 24. Archaeologist Building — `Done (playtest-confirmed 2026-08-12)`
- **User spec (verbatim, 2026-08-11)**: "Archeologist building. Capitol only building. - sends out
  expeditions. Takes 7 days. Random 5 cards that the player does not have/already own. No mythic.
  25% to also get a booster. 5% chance get an item. No mythic items. If you visit before the 7
  days, it will just say x days remaining before the expedition returns. Same sort of interface as
  when you win a duel and get rewards, you flip over the cards to see what you received."
- **Superseded the same day (playtest round 2): NOT a standalone map object.** The first version
  (below, struck through in spirit) placed a dedicated `archaeologist.tx` object directly on
  `player_capital.tmx`, modeled on Arena/Spellsmith. User follow-up: "I don't want that... put it
  under the Utility sub menu so it can be built on one of the pre-existing destroyed building
  spots." Rebuilt as `EconomyBuildings.ARCHAEOLOGIST` (type 9), the same one-per-town/Capitol-only
  economy-building machinery as Outlook/Teleporter - buildable via the Capitol's Utility submenu on
  any ordinary destroyed shop slot, no dedicated map position at all. The old
  `archaeologist.tx`/`player_capital.tmx` object are both deleted.
- ~~New Capitol-only building, dedicated template (not a shop conversion): modeled on Arena/
  Spellsmith rather than Bank/Exchange/Armory... a new object (id 102) placed on
  `player_capital.tmx` at (208, 140), and a new "archaeologist" case in MapStage.java...~~
  (superseded above, kept struck-through for the "why" trail rather than deleted outright).
- **Timer**: new `PointOfInterestChanges.archaeologistExpeditionSentDay` (single int field, -1 =
  no expedition active - not objectId-keyed like `buildingLevels`/guard fields, since there's only
  ever one Archaeologist). "Send Expedition" stamps the current in-game day; visiting before 7 days
  have elapsed shows "Expedition in progress - X days remaining"; visiting at/after 7 days shows
  "Collect Rewards", which resets the timer to -1 and routes into `RewardScene` with
  `Type.Loot` - the exact same flip-to-reveal interface duel wins already use, per the user's
  explicit ask.
- **Rewards** (`EconomyBuildings.generateExpeditionRewards()`): 5 cards from the pool of
  Common/Uncommon/Rare cards the player doesn't already own by name (`AdventurePlayer.current().
  getCards()`, matched by `PaperCard.getName()` so a different edition/printing of an already-owned
  card still counts as owned). **Set-diversity requirement added same day (playtest round 2)**:
  the 5 cards must now come from 5 DIFFERENT expansions (`PaperCard.getEdition()`) per explicit
  user spec - greedily picked from the shuffled non-owned pool, skipping any card whose edition is
  already represented in this batch. Recomputed fresh from the player's live collection on every
  visit, so a card already claimed from an earlier expedition won't be offered again. 25% chance of
  an additional real booster pack (reuses the existing `"cardPackShop"` `RewardData` type - the
  same mechanism Booster Pack Shops use - picking any legal, obtainable edition at random). 5%
  chance of an additional item from a new 542-entry non-Mythic item pool (Common+Uncommon+Rare,
  non-quest - same `rarity` + `questItem` exclusion query already used for the Arena Challenge
  pools, just spanning all three tiers unweighted since the user's spec wasn't tier-split for this
  roll).
- **Cost added (playtest round 2): 1000 gold to send an expedition.** The first round shipped this
  free by default (flagged explicitly as an unconfirmed assumption, not a design decision) - user
  confirmed same day it should cost 1000g, now charged upfront on "Send Expedition" (only enabled
  when affordable, same pattern every other paid action in this mod uses).
- **Real art added (playtest round 2): buildings.png IDs 722/723/750/751** (user-specified, a 2x2
  block) - replaces the generic SpecialShop placeholder the first round shipped with. Visually a
  teal guardian-statue-like structure, not obviously "archaeology"-themed - flagged for awareness,
  not second-guessed, since the user gave these exact IDs directly (the same 4 IDs an earlier
  round had actually rejected on sight; re-specifying them this round reads as confirmed intent).
- ~~No real art identified - tile 751 alone turned out to be part of an unrelated teal
  guardian-temple sprite, `getArchaeologistSprite()` fell back to the generic SpecialShop icon.~~
  Superseded above - the full 2x2 block (722/723/750/751) reads as a distinct structure even
  though 751 alone didn't.
- ~~Map placement checked against the collision layer at (208, 140) on `player_capital.tmx`,
  never visually confirmed.~~ Moot - there's no fixed map placement anymore (see the
  Utility-submenu redesign above), so this question no longer applies.

### 25. Player Deck-Building Engine — `Done (2026-09-01, user decision - existing deck editor is sufficient)`
- User idea (2026-08-11, wishlist batch): "Building your own engine to play your decks." Not yet
  scoped or discussed in detail - reads as wanting in-game tools to construct/tune a deck around a
  specific strategy ("an engine"), distinct from #20's Deck Tester (which pits two ALREADY-BUILT
  saved decks against each other, no construction help involved). Could mean anything from deck-
  archetype suggestions to a guided deckbuilding wizard - needs a scoping conversation before
  design, ideally against `AdventureDeckEditor.java` (the mod's existing in-game deck editor) to
  see what's already there versus what this would add.
- **CLOSED 2026-09-01 (user decision): the existing in-game deck editor covers this.**
  `scene/AdventureDeckEditor.java` is a full 1,164-line editor with search, filtering and
  collection browsing, and #20's Deck Tester sits alongside it for evaluating what you build. No
  separate "engine" is wanted on top. Reopen only if a specific missing capability turns up in
  play, and scope THAT rather than this broad ask.

### 26. Research: External Feature Requests — `Removed (2026-08-12, user decision)`
- Was: a research task to mine Forge community channels for feature ideas. Cut from scope.

### 27. Simulate Level 2 Arena Battles — `Done (2026-08-22, user-confirmed complete)`
- User idea (2026-08-11, wishlist batch): "Simulate lvl 2 arena battles." Ties to #20 (Upgradable
  Arena) - not yet clear whether this means an auto-resolve/fast-forward option for a Challenge
  Arena bracket (skip watching every fight play out), a balance-testing tool (run N simulated
  brackets and report win rates for tuning the Challenge pool), or something else. Needs
  clarification before scoping.
- **Groundwork (researched 2026-08-06 under the since-removed #8, moved here 2026-08-12): how
  Forge actually resolves AI-vs-AI fights.** It's a hybrid - any fight involving the human runs
  the real duel engine (`DuelScene.initDuels()`), but AI-vs-AI fights use statistical shortcuts:
  `ArenaScene.setWinner()` weights a random roll by each fighter's `life` stat, and
  `EventScene.startRound()`'s Inn tournament AI rounds are a flat 50/50 (marked `//Todo: Actually
  run match simulation here` in stock code). The engine CAN run two-AI matches headlessly
  (`forge-gui-desktop`'s `SimulateMatch.java` proves it) but Adventure mode never wires that up -
  `DuelScene` assumes one side is human. **Stat gotcha:** `EnemyData` has no single "power"
  field - `life` is what the arena formula weighs, `difficulty` only affects deck-tier selection.
  A real simulated duel per fight is likely too expensive to run routinely; stat-weighted RNG for
  routine sims, reserving real simulation for rare/important battles, was the leaning.

### 28. Promo Write-Up — `Done (2026-08-22, user-confirmed complete)` (writing task, not a build task)
- User ask (2026-08-11, wishlist batch): "Do promo write up." A marketing/announcement write-up for
  the mod (for a release post, Discord, Reddit, wherever it'd be shared), not a code change. Should
  probably wait until #39 (easy deployment/sharing) is further along, so there's something concrete
  to point people at.

### 29. Extra Attacking Mage per 10 Player Cities — `Done (playtest-confirmed 2026-08-18)`
- User spec (2026-08-11, wishlist batch): "Add 1 extra attacking mage per 10 players cities." Ties
  into #7 (Dynamic Territory Control)'s existing per-color simultaneous-mage cap
  (`TerritoryControl.maxActiveMagesPerColor()`, currently scales 2/3/4/5 with difficulty tier only).
  This would add a second scaling axis - the more towns/capitals the PLAYER personally owns, the
  more attacking mages are active at once (across all 5 colors, or per-color? not yet clarified) -
  presumably a rubber-band mechanic so a dominant player faces escalating pressure. Needs scoping:
  does "10 player cities" mean total owned towns+capitals, and does the bonus mage count apply per
  color or as a shared global increment.
- **Built, round 8 - fully specified by the same follow-up message**: "This is for normal, add 1
  town to easy difficulty, so 11 and subtract 1 for hard and insane, so insane would be +1 attacker
  per 8 cities" - i.e. Easy 11, Normal 10, Hard 9, Insane 8 towns per bonus mage, which resolves
  cleanly to `11 - difficultyIndex` needing no per-tier table. Applied PER-COLOR (each color
  independently gets the same bonus, added on top of the existing flat difficulty base) - the
  simpler of the two readings, and consistent with `maxActiveMagesPerColor()`'s own existing
  per-color scope. "Count Capitol as a town" resolved via `TownRestoration.countPlayerTowns()
  + (capitolExists() ? 1 : 0)` - the EXACT same expression the town life-bonus calc already uses
  for the identical "does the Capitol count" question, `countPlayerTowns()` itself promoted from
  private to public to make the reuse possible. Not yet playtested - needs several in-game days at
  a fast time-multiplier with 10+ owned towns to actually observe the bonus mage count changing.

### 30. AI-Generated Decks for Arena Enemies — `Done (2026-08-12, user-confirmed)`
- Fulfilled by the 5 Challenge Arena champion decks (#42): AI-built (Claude-designed,
  script-validated for color/rarity/legality constraints), added to the Challenge pool as
  arena-exclusive enemies with signature bounties. User confirmed this satisfies the original
  "AI deck builds - add to arena" ask; the alternative genetic-AI-per-bracket idea sketched here
  is dropped with it.

### 31. Custom Building Ruin Art Variety — `Done (2026-09-01, user decision - existing ruin art is sufficient)`
- User idea (2026-08-11, wishlist batch): "Custom building ruins." Ties directly to #2 (Central
  Wasteland & Town Reconstruction), which already has real hand-made ruin art for both the town-icon
  level (16 variants) and the shop level (64 variants, `RubbleOverlay`/destroyed-shop art) - not yet
  clear whether this wants MORE variety on top of the existing sets, ruin art for building types
  that still fall back to the generic Job Board rubble overlay (per #2, the Job Board itself has no
  dedicated art yet), or something else entirely (e.g. ruins that visually reflect the building type
  that stood there, not just a generic broken-shop look). Needs clarification.
- **CLOSED 2026-09-01 (user decision): the art already shipped under #2 is enough.** 16 town-icon
  ruin variants and 64 shop-level destroyed-shop variants (`util/RubbleOverlay.java` plus the
  per-shop rubble art), and round 72 gave ruined slots one shared visibility rule so signs and
  color bars hide correctly. No further per-building-type ruin art is planned.

### 32. Shop Type Re-Roll — `Done (playtest-confirmed 2026-08-18)`
- User idea (2026-08-11, wishlist batch): "Shop type re-roll." Ties to #10 (Economy Buildings) - a
  way to change which shop type occupies an already-built slot (e.g. swap a built Gold Mine for a
  Bank) rather than the current one-shot choice made at build time. Needs scoping: paid or free,
  any cooldown/limit, and whether "shop" here means the 6 special economy-building types (#10) or
  also the ordinary ambient Card/Item/Booster shops that spawn with a town.
- **Built, round 8 - scope resolved by a fuller follow-up spec**: "For all Card-shops, add a
  re-roll card shop type for 50 shards. This will randomly pick a new card shop type. Change the
  little bulletin board in front of the shop also on re-roll to match new shop type." - so the
  ordinary ambient card shops (not the 6 economy-building types, which have their own separate
  build-menu system entirely, #10), 50 shards (difficulty-scaled like every other cost this
  session), no cooldown, and the visible exterior sign updates too. New `RewardScene.
  promptRerollShopType()` (button shares a row with Armory's own `rerollButton`, since a shop is
  never both an Armory and an ordinary card shop at once) delegates to a new `MapStage.
  rerollShopType()`, which picks a NEW `ShopData` from the SAME raw comma-list candidate pool this
  specific object's tmx `commonShopList`/etc. property offered at load time (captured once, at
  load, into a new `shopCandidatePools` map - naturally excludes Armory/land shops, whose tmx
  properties are always single names not comma lists, and Rotating shops, which already have their
  own date-seeded re-roll), pins the pick via the same `PointOfInterestChanges.
  setPinnedShopName()` the Capitol migration already established, regenerates the displayed
  inventory (`RewardData.generate()`, same pattern `restockShop()` uses), and swaps the live sign
  sprite's texture in place via a new `TextureSprite.setRegion()` (the sign was previously an
  immutable-region sprite with no way to change its art after construction).
- **SUPERSEDED 2026-09-01 (doc drift caught by the round-79 audit).** Everything above describes the
  mechanic as it was in v1.03, and it no longer exists. Round 71 DELETED the flat 50-shard random
  re-roll: `RewardScene.promptRerollShopType()` is gone, and the button now reads **"Re-assign Shop
  Type"**, charges **gold + wood** rather than shards, offers a **menu** rather than a random pick,
  and refuses entries that are blueprint-locked (see #92). The underlying `MapStage.rerollShopType()`
  survives as the destroy-and-rebuild path only. Do not go looking for a "Re-roll Shop Type
  (50 [+Shards])" button - its absence is correct, not a regression. The Done marker above refers to
  the ORIGINAL feature as shipped and playtested on 2026-08-18; the replacement is covered by #92
  and has its own (unconfirmed) status there.

### 33. Early Armory Inventory Re-Roll — `Done (playtest-confirmed 2026-08-13)`
- User idea (2026-08-11, wishlist batch): "Re-roll armory inventory early." The Armory's item stock
  currently restocks automatically once a week (#18's Item Economy, `EconomyBuildings`'s weekly-
  restock sweep, MOD_CHANGELOG's "Weekly shop restock" work) - this would let the player pay to
  force an early restock instead of waiting out the week. Needs scoping: gold cost, and whether it
  resets the weekly timer (so the NEXT free restock is delayed) or just adds a bonus roll on top.
- **Built same day, round 7, filed under #18's own "Manual 'Re-roll' button" bullet** (this entry's
  own status line just hadn't been updated to match until now) - 100 shards base cost, difficulty-
  scaled, on its own separate 7-day cooldown that deliberately does NOT interact with the automatic
  weekly timer (per explicit user spec: "The re-roll button in independent from the weekly
  re-fresh") - so it's the "bonus roll on top" resolution to this entry's own open question, not
  the "resets the timer" one. See #18 for full implementation detail.

### 34. Update Mod Intro Text — `Done (2026-08-22, user-confirmed complete)` (writing task)
- User ask (2026-08-11, wishlist batch): "Update mod intro text." The player-facing text shown when
  starting/selecting "The Forsaken Realms" plane (New Game screen and/or a title/lore blurb) -
  needs the actual current text located and a replacement drafted with the user, not scoped further
  yet.

### 35. Rename Capitol to "Orazca" — `Done (playtest-confirmed 2026-08-13)`
- User spec (2026-08-11, wishlist batch): "Capitol name Orazca." The player's Capitol currently has
  internal name `"Player Capitol"` (`TownRestoration.CAPITOL_POI_NAME`, #13) with displayName
  "Camelot" set at upgrade time (`upgradeToCapitol()`'s `transformInto()` call, #13). Checked
  earlier this session: "Orazca" isn't used as a place/building name anywhere in this plane - it
  only appears inside a few starter `.dck` files as individual Magic card names (Ixalan cards like
  *Kumena, Tyrant of Orazca*), and separately as an unrelated Conquest-mode plane elsewhere in
  Forge - so renaming is a plain, conflict-free swap of that one displayName string, no other
  cleanup needed.
- **Implemented, round 7**: the real, functional value lives in `points_of_interest.json`'s
  `"Player Capitol"` template entry (`"displayName": "Orazca"`) - `transformInto()` reads it from
  there, nothing hardcoded in Java. Also swept every OTHER "Camelot" occurrence for consistency
  (all comments/log lines, no other functional value): `TownRestoration.java`'s upgrade
  notification text ("Orazca rises!..."), its console log line, and two explanatory comments in
  `TownRestoration.java`/`TerritoryControl.java`. Confirmed zero remaining "Camelot" references
  anywhere in the mod's own Java source or `The Forsaken Realms` resource folder.

### 36. More Terrain Customization — `Merged into #11 (2026-08-12, user decision - same ask)`

### 37. Graph on Info Screen — `Done (playtest-confirmed 2026-08-18)` (see #63)
- User idea (2026-08-11, wishlist batch): "Graph on Info Screen." Which "Info Screen" and which
  data isn't yet specified - candidates already in the mod: `WorldStandingsScene` (#7, per-color
  town count/reputation/status table) or `PlayerStatisticScene` (existing stock stats screen). A
  graph implies something tracked OVER TIME (reputation trend, territory size trend, gold/day?),
  which isn't currently logged as a time series anywhere in the mod - would need a new persisted
  history buffer before any graph could plot real data. Needs clarification on both the screen and
  the metric before scoping.
- **"Info Screen" confirmed to mean `WorldStandingsScene`** (round 8, via #38's own build - see
  that entry) - one half of this item's ambiguity resolved. Still needs the metric clarified
  before this can be scoped/built; the new time-series-buffer requirement is unchanged.
- **Built, round 2026-08-15, per #63**: a per-color town-count bar chart, filling the blank spot
  the user flagged directly ("maybe a graph"). Deliberately a CURRENT-state snapshot rather than a
  time-series trend - the history-buffer prerequisite flagged above is still real and unbuilt, so a
  true trend line (reputation/territory-over-time) remains its own future item if wanted.

### 38. Reputation Tier Explanation on Info Screen — `Done (playtest-confirmed 2026-08-14)`
- User idea (2026-08-11, wishlist batch): "Explanation of Reputation tiers on Info Screen." Likely
  `WorldStandingsScene` (#7), which already shows each color's live Reputation number/tier color
  but never explains what the 5 tiers (Partner/Happy/Neutral/Unhappy/War, #1) actually DO -
  straightforward in spirit to #20's just-built Outlook info-dialog text (explain a mechanic in
  plain language instead of leaving the player to infer it) - could reuse #1's own tier table
  (price modifiers, targeting-odds shifts, entry bars) as the source text almost verbatim.
- **Built, round 8** (user follow-up: "On the Info Page... Create a button, 'reputation' and
  please create a table of what each level entails") - confirms `WorldStandingsScene` really is
  what "Info Screen"/"Info Page" refers to (also resolving #37's own open question about which
  screen). New `reputationInfo` button opens a plain info dialog listing all 5 tiers with their
  real numbers - cross-checked directly against `ColorReputation.java`
  (`getShopPriceMultiplier()`/`getPlayerTownAttackWeight()`/`isEntryBarred()`/`isHealBarred()`/
  `CAPITAL_ENTRY_TOLL`) rather than recalled from an earlier, slightly-stale version of this same
  table, so the wiki text can't drift from what the tiers actually do. A SECOND, not-previously-
  requested "Expansion" button was added alongside it in the same round (see #7's own note) -
  the user's message covered both together as one "wiki" ask.

### 39. Easy Mod Deployment/Sharing — `Done (2026-08-22, user-confirmed complete)`
- User idea (2026-08-11, wishlist batch): "Mod deployment/sharing made easy." Today's deploy process
  (per `CLAUDE.md`/this session's own established loop) is manual and dev-oriented: compile, splice
  the compiled `forge/adventure` package into an already-installed Forge jar via `jar uf`, then
  mirror `forge-gui/res/adventure/The Forsaken Realms/` on top of an existing install - nothing a
  non-technical player could do. This would need something closer to a single distributable
  package/installer another player could drop onto a stock Forge install (or a from-scratch bundled
  build) without needing Maven/a JDK/manual jar surgery. Needs scoping: are we packaging for players
  who already own/run stock Forge, or a fully standalone build; ties naturally into #28 (promo
  write-up) once this exists, since there'd finally be something easy to point people at.

### 40. Skip Tutorial — `Done (playtest-confirmed 2026-08-13)`
- **User request (verbatim)**: remove the two dead buttons on the intro dialog ("I want to find
  the planeswalkers (Future release)" / "I want to make a name for myself (Future release)" - both
  were already `isDisabled: true` in the data, hence "dead"), add a "Skip tutorial" button. Skipping
  grants the same rewards the wizard normally gives and spawns the player right outside on the main
  map next to the campfire, skipping the rest of the intro/tutorial quest chain (find a town, find
  a dungeon, find a cave) entirely.
- **Traced the full intro flow before touching anything** (`quests.json`): the cave dialog (quest
  28, "Entering The Forsaken Realms") issues quest 53 ("Welcome to The Forsaken Realms" - talk to
  the cave mage, exit the cave), which on exit issues quest 30 ("Where Am I?" - the actual "find a
  town / find a dungeon / win a duel / find a cave / find a town again" tutorial chain the user
  described). Confirmed the ACTUAL "wizard reward" isn't in this JSON at all - it's a second wizard
  NPC (object id 69) standing at the "Spawn" POI's own map (`spawn.tmx`, main_story), whose first-
  conversation dialog grants a `Colorless rune` (teleport-home item) and, on a separate branch, 3x
  Bronze Challenge Coin + 1x Challenge Coin + 1x Silver Challenge Coin - gated behind
  `freeChallengeCoins`/`mainQuest` flags so it only ever fires once. This is genuinely what "the
  wizard gives you" refers to, not anything in the cave itself.
- **Removed** both disabled options from quest 28's `prologue.options`. **Added** a single new
  top-level option, "Skip tutorial - just get me to the game", whose `action` list directly grants
  everything the Spawn wizard would have (all 4 items, `freeChallengeCoins`/`mainQuest` character/
  quest flags set so a later walk-up to that NPC doesn't re-offer or double-grant), sets the
  existing `noQuest` character flag (already used, but previously only wired to one condition
  check, by the pre-existing New Game+ skip option two rows below - see `MOD_CHANGELOG.md`), and
  teleports the player straight to Spawn. Deliberately issues no quest at all (53/30 never start),
  so there is nothing left dangling to "skip" - the find-town/dungeon/cave chain simply never
  begins for a player who picks this option.
- **New engine capability needed for the teleport, not previously possible from quest dialog data**:
  `DialogData.ActionData.runCommand` (new field) reuses `ConsoleCommandInterpreter`'s existing
  `"teleport to poi <name>"` command (already used by the debug console and the Colorless rune
  item's own `commandOnUse`) - see `CORE_ENGINE_CHANGES.md`'s `DialogData.java`/`MapDialog.java`
  entries. Confirmed the exact command syntax against the already-working item definition
  (`"teleport to poi Spawn"`, no quotes - the interpreter's tokenizer splits on whitespace only, so
  a quoted single-word name would pass the literal quote characters through and fail to resolve).
- **Single click, not a confirm-then-apply flow**: the new option's `action` fires immediately when
  clicked (`MapDialog.loadDialog()` runs `setEffects()` before rendering anything further), and with
  no `text`/`options` of its own it hits the code's own documented "empty dialog as an area-effect
  trigger" early-return path - dialog just closes. Deliberately not modeled on the pre-existing "New
  Game+ skip" option below it (which shows one extra "(Continue)" confirmation screen first) - the
  user's stated goal was fewer clicks ("I'm tired of clicking all those menus"), and a second
  confirmation screen would also race visually against the teleport's own screen transition.
- Not yet playtested - needs a fresh New Game to click through (both the normal path and this new
  skip path), and specifically to confirm the item grants/teleport all land correctly and that
  walking up to the Spawn wizard afterward doesn't re-offer the same conversation.

### 41. Content Filter Tables — `Done (playtest-confirmed 2026-08-18)`
Three auto-generated, user-editable CSVs in the plane's "config tables/" folder (expansions /
items / enemies), each row full entity details + Include Y/N - flip to N to remove that content
from the game. Quest content protected; user edits survive updates; see MOD_CHANGELOG.
- **User couldn't find the CSVs (2026-08-13) - because they'd never actually been generated
  anywhere, on either machine.** These are lazily runtime-generated on first use (`ContentFilterTables`),
  not pre-authored/committed content - `git log --all -- "**/*.csv"` confirmed zero CSVs have ever
  been committed. `items.csv`/`enemies.csv` now seeded directly into the repo (a Python script
  reproducing `filterItems()`/`registerEnemies()`'s exact column logic against the plane's own
  `items.json`/`enemies.json` - 628/1474 rows, all `Include=Y`), so they're real, git-tracked,
  editable files going forward instead of living only in whichever machine's deployed install last
  generated them (explains the recent "items.csv Notes column" commit - that edit was made
  directly to a live-generated file, never committed). **`expansions.csv` NOT seeded** - it needs
  Forge's live card/edition database (`FModel.getMagicDb()`), which in turn needs the full
  `GuiBase` app-bootstrap chain (confirmed by trying: a headless harness crashed on
  `ForgeConstants`'s static init needing `GuiBase.getInterface()`) - not safely reproducible
  standalone without real risk of a subtly wrong edition list. **Will appear automatically the
  first time the game actually runs** with `contentFilterTablesEnabled` on (same mechanism that
  already produced `items.csv`/`enemies.csv` content on whichever machine ran this feature before) -
  no manual step needed beyond that; once it exists, commit it like any other plane file so both
  machines share it. All-`Y` baselines have zero functional effect either way (the exclusion set is
  empty until the user actually flips a row to `N`) - the two seeded tables are ready to edit now.

### 42. Challenge Arena Champions + Themed Drops — `Done (playtest-confirmed 2026-09-01)`
5 hand-built champion decks (Dovin Baan WU, Kaervek BR, Sidar Kondo GW, Meren BG, Domri Rade RG -
script-validated for color/rarity/legality constraints) added to the Challenge pool as
arena-exclusive enemies; full-bracket wins pay their signature bounty, and every Challenge run
drops 1 Rare+ card themed to the last defeated foe.

### 43. Multi-Resource Building Costs — `Done (playtest-confirmed 2026-08-12)`
Every construction/upgrade re-priced per the user's cost table, mixing Gold/Wood/Stone/Shards
(full table in MOD_CHANGELOG); new [+Wood]/[+Stone] cost glyphs added to the plane's items
atlas. Gives Lumber/Stone (#9) their first real sink.

### 44. Guard Payment Priority + Bank Preferences — `Done (playtest-confirmed 2026-08-13)`
User spec: weekly guard salaries now pay the Capitol's own guards first, then every other owned
town with a guard in order of increasing distance from the Capitol (`townsByCapitolPriority()`).
Two new checkboxes in the Bank dialog, both checked by default: **"Pay Guards from Bank first"**
(Gold-only - a guard's own town's bank balance is drawn before the player's inventory; unchecked
reverses that order; either way still dismisses the guard if the combined total falls short) and
**"Gold Mine deposits into Bank Directly"** (that town's Gold Mine production credits its own bank
instead of the player's inventory, when that town has a Bank built). Shards (Mythic/"Grandmaster"
tier only) are untouched by either checkbox in every path, always paid from inventory as before.
Practical scope note: since Bank can currently only be built at the Capitol, both checkboxes are
effectively Capitol-only today - an ordinary town's guard (max 1) always pays 100% from inventory
regardless of the setting, and a Gold Mine anywhere but the Capitol always deposits to inventory.
Flagged rather than special-cased; revisit if Bank ever becomes buildable elsewhere. Full technical
detail (including a bug an adversarial review pass caught before deploy - a destroyed Bank's
orphaned balance would otherwise have stayed silently spendable on guard salaries) in
MOD_CHANGELOG.md. Not yet playtested - needs a 100x-Speed fast-forward past a guard's due salary
date to actually see the bank-vs-inventory split happen.
- **Guard-disbanded notification - confirmed already built, no new code needed (user asked
  2026-08-13)**: `EconomyBuildings.processDaysPassed()`'s salary loop already calls
  `GameHUD.addNotification("[RED]Your <tier> guard was disbanded - salary went unpaid!", true)`
  the moment a guard's combined bank+inventory gold (or shard) shortfall forces a disband - same
  `addNotification(text, authoredMarkup)` pattern as the mage-attack "PLAYER OWNED TOWN!" warning
  the user was comparing it to. Been in place since the original Guard Hiring build (#22,
  2026-08-11) and untouched by this round's payment-priority changes; worth knowing it exists
  before re-asking for it.
- **Bank dialog too tall / ran off-screen, fixed (2026-08-13)** - user report + screenshot: with
  the two new preference checkboxes above added on top of the existing 6 full-width action-button
  rows, the dialog grew taller than the screen and clipped its own header/Deposited-balance/
  interest-rate rows off the TOP (`Dialog.setKeepWithinStage()` can reposition a dialog but can't
  shrink one taller than the stage). Deposit 100/Deposit All/Withdraw 100/Withdraw All now use the
  same half-width-buttons-packed-2-per-row treatment already established for the Exchange dialog's
  Buy/Sell pairs and the Manage Guards dialog's Hire/Dismiss pairs (`addHalfButton()`), cutting 4
  rows down to 2; Destroy Building/Close stay full-width singles, matching the Exchange dialog's
  own convention. No font scale-down needed - every label here is shorter than "Dismiss Uncommon"/
  "Dismiss Mythic", which already fit this same button width unscaled. The "Deposited: N [+Gold]"
  balance line was never actually missing from the code (`refreshBankDialog()` has always shown
  it right after the "Bank" header) - it was just off-screen along with the rest of the top of the
  dialog; shrinking the button area should bring it back into view. Not yet playtested.
- **Fixed weekly payday for everyone, replacing the per-guard rolling timer (user spec,
  2026-08-13)**: every hired guard now pays on the same shared calendar days - 7, 14, 21, 28, etc.
  - regardless of when it was individually hired, instead of "7 days since THIS guard was last
  paid." `EconomyBuildings.processDaysPassed()`'s salary loop now computes each guard's next due
  day as the smallest multiple of 7 strictly greater than its `lastPaidDay`, so a guard hired on
  day 10 first pays on day 14 (not day 17). Existing guards (including ones carried over from
  before this change, whose `lastPaidDay` isn't a multiple of 7) snap onto the shared schedule
  automatically the next time they're due - no save migration needed. Bank-first/inventory-first
  source ordering and Shard-untouched behavior from the change above are unaffected - only the
  TRIGGER timing changed. Not yet playtested - needs a couple of hired guards at staggered hire
  days, then a 100x-Speed fast-forward past day 7/14 to confirm they all get charged together.

### 45. Capitol Land-Shop Ruins, Torch Item, Resource-Pickup Sparkle — `Done (playtest-confirmed 2026-08-14)`
Three pieces of art/content polish from one user round, all user-provided art reviewed before use:

- **Capitol land-shop ruins**: the 6 fixed land shops in the Capitol (id 55 White/Plains, 77
  Green/Forest, 78 Red/Mountain, 79 Black/Swamp, 80 Blue/Island, 81 Neutral/Land - confirmed
  against `player_capital.tmx`'s own `commonShopList` properties) were showing a random pick from
  the generic 64-variant broken-shop pool while unrepaired, instead of anything color-matched.
  Now use 6 new dedicated 16x16 ruins (user-provided, packed into a new
  `maps/tileset/land_shop_broken.atlas`), guarded to the Capitol specifically so an unrelated shop
  in some other town template sharing the same raw object id never picks this up (the exact bug
  class already hit once for the generic pool).
- **Torch item** (user's first custom item added this session): Common, 100g, `Ability2` slot, not
  a quest item, `effect.visionRadiusMultiplier: 3.0` - triples the player's live FoW vision radius
  (Stage 3 around the player, see #3's stage table) while equipped. New `EffectData.
  visionRadiusMultiplier` field + `AdventurePlayer.visionRadiusMultiplier()` (same pattern as the
  pre-existing `equipmentSpeed()`/`goldModifier()`) + `World.getVisionRadius()` now applies it -
  the exact spot `visionRadius`'s own field comment had already flagged for this ("items will
  raise this later"). Automatically eligible for the Armory's weighted Common pool from level 1
  (no separate wiring needed - `ItemListData.getItemNamesByRarity()` already draws from the whole
  catalog by rarity). Source art (64x64, no transparency - flagged and fixed) had its background
  flood-filled to transparent and was downsampled to 16x16, added onto a plane-local copy of the
  shared `items.atlas`/`items.png` (new 16px canvas row appended, zero existing item pixels
  touched) rather than a separate atlas, to keep every OTHER item's lookup working unchanged.
- **Resource-pickup sparkle for all 5 types**: Gold already drew a real 4-frame sparkle animation
  (`sprites/gold.atlas`, stock `treasure.png`); Wood/Stone/Shards/Random ("Mystery") only had a
  coded alpha fade in/out. User provided a new shared sprite sheet (`resource_drop.png`) plus 5
  matching `.atlas` files (including a new Gold one) - `WorldStage`'s sparkle mechanism generalized
  from Gold-only to all 5 `ResourceSpawns.TYPE_*` constants; the alpha-twinkle code path stays only
  as a defensive fallback if an atlas somehow fails to load. Gold's sparkle now uses the user's new
  art too (same `GOLD_ATLAS` constant, now resolving to a plane-local override instead of the stock
  file - the established plane-first override mechanism, no code path change needed).

Not yet playtested - none of these three have been seen rendered in-game.

### 46. FoW Stage-3 Reveal Gap on Owned Town Land — `Done (playtest-confirmed 2026-08-18)`
User report + screenshot: standing on owned land, fog-of-war still rendered fully dark (Stage 1)
instead of the expected full reveal (Stage 3) - a variant of a bug class fixed once already today
for the Capitol specifically (see #45's sibling fix, `fb1da89593a`), but this report was about an
ordinary player town, a different code path that fix never touched. Root cause:
`TerritoryControl.processTerritoryExpansion()`'s daily town-growth block revealed only the RAW
territory radius, not the actual (Outlook-aware, up to 2x) vision circle
`rebuildPlayerTownVision()` caches for the same town one line above - so a town with an Outlook
had ground marked "owned"/Stage-3-eligible that the fog `explored[][]` array never actually got
told to reveal, permanently stuck black past the raw radius. Two-part fix: (1) that reveal call
now uses the same Outlook-aware radius every other reveal site uses, via a shared
`TownRestoration.applyTownVisionReveal()` helper (generalized from a Capitol-only version added
earlier today, now called from 3 sites instead of duplicated); (2) a new
`TownRestoration.repairAllTownVisionReveal()` self-heals EVERY restored town's vision reveal on
save load (not just the Capitol), so the user's already-affected save recovers automatically next
load rather than needing the bug to never have happened.

- **Still broken after the above, real root cause found (2026-08-13, same day)** - user retest:
  still Stage-1 black on clearly-owned Capitol land. The actual gap was a THIRD, still-untouched
  code path: `TerritoryControl.processTerritoryExpansion()`'s Capitol daily-territory-expansion
  block (paints "player" ownership out to `MAX_TERRITORY_RADIUS`=450 via `claimWastelandRing()`)
  had its own `revealArea()` call deliberately REMOVED on 2026-08-11, after the user reported a
  "huge ~450-radius Stage 2 FoW circle" appearing around the Capitol - a real, correctly-fixed
  complaint under the OLD spec, where territory ownership and fog discovery were meant to stay
  separate. The user's spec changed today ("wherever the player's lands spread should all be
  revealed... lose land, lose vision") and directly supersedes that 2026-08-11 decision - the
  reveal is re-added, this time keyed to the block's own actual growing radius (not the old
  version's whole-450-disc-regardless), so it never over-reveals beyond what was genuinely just
  claimed. The forge.log from the user's own test session directly confirmed the bug: "Capitol
  territory radius now 56/450" growing to 65, then 92 - well past the ~20-60 tile fixed vision
  circle every prior fix in this item was still capped at. Plus a matching self-heal extension so
  the user's ALREADY-affected save (radius already grown large before this fix landed) recovers on
  next load instead of only future growth being covered. **Caught in adversarial review before
  deploy**: the first version of this fix re-ran the (expensive, unclipped, up to ~490,000-tile)
  reveal/refresh pair on every day ANY unrelated map activity happened, even long after the
  Capitol's own territory had already maxed out - fixed by gating it on the radius having actually
  grown that specific day. Not yet playtested - needs the user to load their save and confirm the
  large black band around the Capitol is now revealed.

### 47. Armory Level 2 Not Showing 8 Items Immediately — `Fixed (playtest-confirmed 2026-08-14)`
User report: Armory L1 correctly sells 6 items, L2 should sell 8 (per #9's dynamic item-pool
rework) but still showed only 6 right after upgrading. Root cause: `RewardScene.promptUpgradeArmory()`
only flipped the persisted Level-2 flag and toggled two buttons - it never re-resolved the shop to
its L2 shops.json entry or regenerated/redrew the item grid, so the screen the player was already
looking at kept showing the stale L1 rewards (leaving and re-entering the town should have already
worked, since `MapStage.loadMap()`'s own L1->L2 redirect is correct - worth the user confirming
that path too). Fixed by mirroring the shop-reroll/Armory-reroll buttons' own pattern: resolve the
L2-suffixed ShopData, swap it onto the shop actor, regenerate rewards with the shop's own
(weekly-refreshing) seed, and redraw immediately. **Caught and fixed by adversarial review before
deploy**: the first draft resolved the L2 data and charged/upgraded FIRST, discovering only
afterward (silently) if no L2 entry existed - a real, reachable trap for a separate pre-existing
data gap (the 5 AI-capital colored `Equipment`/`Items` armory-type shops have no `*L2` shops.json
sibling at all) that would have permanently burned the player's 300 stone with zero visible effect
if they ever captured and upgraded one of those. Reordered to resolve the L2 data BEFORE charging
anything - a missing entry now correctly refuses the upgrade with a notification instead of
silently eating the payment (matches the existing "no-charge-no-change" pattern used elsewhere in
this file for an analogous case). The missing shops.json L2 entries for the 5 colored AI-capital
armories are a separate, still-open data gap - not fixed here, since it needs a design decision
(what should THOSE upgrades even cost/stock) rather than a mechanical copy-paste.

### 48. Trophy Items Leaking Into the Armory Sell Pool — `Fixed (playtest-confirmed 2026-08-14)`
User report + screenshots: found "Chandra's Stone" and "Medal of Ultimate Victory" for sale in the
Armory for 1000 gold, and independently found "Liliana's Stone" too - all three are boss-fight
mementos (Chandra's/Liliana's Stone drop from beating those two planeswalkers; Medal of Ultimate
Victory from beating Meloku, the game's real final/hardest fight) with a working grant path, so
they were never flagged by the earlier item-reachability audit (#41's "Currently Unused" pass) -
but nobody had ever asked the separate question of whether a reachable item is ALSO appropriate
for general sale. Root cause: the Armory's Weighted item pool (`ItemListData.getItemNamesByRarity()`)
only ever excluded quest items and Landscape Sketchbooks - no flag existed for "reachable, but not
meant to be generally purchasable." A full scan (requested by the user) of item flavor text for
boss-trophy phrasing, cross-referenced against enemies.json's guaranteed-drop rewards, confirmed
these are the only 3 items in the catalog that fit this narrow category (as opposed to the ~40
OTHER boss-signature GEAR items like Teferi's Staff/Garruk's Mighty Axe - those are real,
functional equipment that happen to also be a boss's signature drop, and have always been
generally purchasable even before the Armory's dynamic pool rework - pulling those too would be a
much bigger, unrequested change; flagged for the user to decide separately, not done here). Fix:
new `ItemData.excludeFromGeneralSale` boolean (deliberately not `questItem` - that flag also wipes
on New Game+ and disables inventory delete, neither wanted here), wired into
`getItemNamesByRarity()`, set true on the 3 confirmed items in `items.json`. `getItem()` (the
boss-reward grant's own lookup) is untouched, so the actual drops still work. A second, independent
leak in `EconomyBuildings.NON_MYTHIC_ITEM_POOL` (a hardcoded name list backing the Archaeologist's
5% bonus-item roll) also listed all 3 names - fixed by removing them there too. Not yet playtested
- needs a few Armory/Archaeologist rolls to confirm the 3 items no longer appear (probabilistic,
may take several tries either way given how the pool is weighted).

### 49. AI Towns Can't Build/Upgrade Anything + Diagnostic Logging — `Fixed (playtest-confirmed 2026-08-14)`
User spec: "The 5 AI-capital colored armory shops should not be able to upgrade, since they are
all AI controlled. Only the player can build/upgrade stuff... no AI towns/cities should be
touched, besides the Card Expansion limitations to card shops and Inn tournaments." An audit
confirmed every OTHER economy-building action (Bank/Mines/Outlook/Teleporter/Archaeologist/guard
hiring/Destroy Building) was already correctly unreachable at AI towns - structurally, since they
only open via a wasteland-town gate an AI town/capital never satisfies - but RewardScene's
Armory-family buttons bypassed that gate entirely: **"Re-roll Inventory" and "Re-roll Shop Type"
were LIVE and fully functional today at all 5 AI capitals' colored armory-type shops** (a player
could pay shards to reroll an AI capital's stock with zero restriction), and "Upgrade Armory" was
visible/clickable there too, only failing harmlessly by the unrelated shops.json data gap fixed
in #47. Fixed with a new shared `TownRestoration.isCurrentTownPlayerOwned(changes)` check
(player-owned restored town OR the player's own Capitol), gating all 4 buttons' visibility plus
each action's own handler as defense-in-depth. Adversarially reviewed and confirmed correct on
both directions (doesn't miss the 5 AI capitals, doesn't accidentally block the player's own
towns or Capitol) before deploy - see MOD_CHANGELOG.md.

**Diagnostic logging** (standing practice, user request - see `CLAUDE.md`): added `[TFR-MageCap]`
(town-count mage-cap scaling, #29), `[TFR-Targeting]` (AI mage target candidates/weights/roll,
consolidated with mage speed/tier/life), extended `[TFR-Spawn]` with speed/life fields, and new
`[TFR-EnemyLife]` (difficulty-scaled starting life for every fight, not just the day/night-modified
subset `[TFR-DayNight]` already covered). `[TFR-GuardFight]` already covered guard combat odds,
no change needed there.

**Also confirmed (not a bug, no fix needed):** the resource-spawn sparkle art (#45) not appearing
near the starting campfire - there's a one-time 12-tile guarantee on a brand-new game's very first
tick, but it's not a standing invariant (never re-fires, expires with the rest of that spawn's 2-10
day lifetime, no bias back toward the start afterward). Since this project's resource-spawn
playtesting has consistently happened on an existing long-running save rather than fresh New
Games, that one-time window has almost certainly already been spent, long ago, possibly not even
near Spawn. Use the debug console `spawn resource` command (radius 4 tiles) to verify the sparkle
art works right now without waiting/relocating.

### 50. Buttons Not Greyed Out When Unaffordable — `Fixed (playtest-confirmed 2026-08-14)`
User report: "Upgrade Armory" button stayed fully lit (not grayed out) when the player couldn't
afford it, unlike most other cost-gated buttons - asked for a full audit. Found two instances of
the same bug in `RewardScene.java`: `upgradeButton` ("Upgrade Armory") and `shopTypeRerollButton`
("Re-roll Shop Type") were both built/shown with `.setVisible(...)` only, never `.setDisabled(...)`
- relying solely on their own click handlers silently no-oping when unaffordable, unlike
`restockButton`/`rerollButton`/`BuyButton` in the same file (and `EconomyBuildings.java`'s
`addButtonRow()`/`addHalfButton()`/`buildTradeRow()`/the `DialogData.isDisabled` path), which all
correctly wire `.setDisabled()` to a real affordability check. Every other cost-gated button in
both files was confirmed already correct. Fixed by adding the missing `.setDisabled(...)` call to
each, reusing the exact affordability check their own click handlers already use. Not yet
playtested - needs the user to confirm both buttons now grey out correctly when unaffordable.

### 51. Guaranteed Torch on First-Ever Armory Visit — `Done (playtest-confirmed 2026-08-18)`
User spec: the Torch item (#45's vision-radius item) should always be PURCHASABLE from the first
Armory a player builds - normal randomness after that. **Two redesigns now.** First attempt forced
Torch into a Weighted-rarity FOR-SALE slot at generation time only - caught by adversarial review
as a real bug (every other regeneration path could silently reroll it away before purchase). Second
attempt (2026-08-13) swung too far the other way: granted the Torch DIRECTLY to the player's
inventory for free on first Armory visit - immune to staleness, but not what the user actually
asked for (confirmed 2026-08-14 after they found an unbought Torch in their inventory: "I wanted to
guarantee that it would be in the inventory of the Armory... Not add it to player's inventory").
**Third attempt, the one that actually delivers "guaranteed purchasable, not free" without the
original staleness bug**: `EconomyBuildings.injectGuaranteedTorchIfOwed()` is now called from ALL 5
stock-regeneration sites (not just generation time) - a PERSISTENT injection, re-firing on every
weekly refresh/reroll/upgrade for as long as the guarantee is unfulfilled, so it can no longer be
silently rerolled away. Fulfillment now means "the player actually bought a Torch" (hooked into the
Buy button), not "the shop was merely opened." Not yet playtested - needs the user to build their
first Armory and confirm a Torch is in the FOR-SALE stock (not their inventory) until purchased.

### 52. Arena Deck Tester "Simulated" (AI vs AI) Mode — `Done (playtest-confirmed 2026-08-14)`
User request: today's Deck Tester has the player pilot one of two chosen decks against an AI
piloting the other ("Coin Flip" in this write-up, matching the user's own framing - there's no
actual coin flip in the code, the player explicitly picks both sides via two dialogs). Add an
option to have BOTH decks AI-piloted instead, for a fully-automated, watchable matchup the player
doesn't have to manually play. Forge's core engine already has a ready-made, fully-working
watchable AI-vs-AI match path (`HostedMatch`'s `humanCount==0` spectator branch +
`WatchLocalGame`, already exercised elsewhere in the engine) - no core-engine file needed
touching, just two mod-plane files. `DuelScene.initDuels()` gained an `aiControlsPlayerSide`
parameter that swaps `GamePlayerUtil.getGuiPlayer()` for `GamePlayerUtil.createAiPlayer(...)` on
the "player" seat; `ArenaScene.java`'s Deck Tester flow gained a "Choose a mode" dialog ahead of
the existing two deck-picker dialogs (Coin Flip's own flow is byte-for-byte unchanged), plus a new
`launchDeckTesterSimulated()` mirroring the existing launch method. **Caught by adversarial review
before deploy**: a fully-simulated match's spectator controller has a null `Player` field, which a
pre-existing, unrelated line in `DuelScene.GameEnd()` (mana-shard persistence) would have thrown a
`NullPointerException` on for every single Simulated match - harmless (already caught by a
surrounding try/catch, win/loss reporting unaffected) but printed a guaranteed stack trace to
`forge.log` every time, contrary to this project's own clean-logging standard. Fixed with a
null-check. Not yet playtested - needs the user to run a Simulated match and confirm it plays out
and reports a winner correctly with no stack trace in the log.

- **Second, different NPE found via direct forge.log review (2026-08-13, same day, extended play
  session)** - user let the game run much longer and asked for a fresh log review. Found 2
  occurrences of a DIFFERENT `NullPointerException` at `DuelScene.java:115`
  (`hostedMatch.getGame()` returning null before `.getMatch().getWinner()`), each immediately
  preceded by a `[TFR-EnemyLife] Deck Tester...` line confirming Deck Tester context - not the same
  bug as the one above (that one guarded `humans.get(0).getPlayer()`; this is on `hostedMatch.getGame()`
  itself). Root cause: stock `HostedMatch.endCurrentGame()` sets `game = null`, and for a Deck
  Tester match (`noAnte`, single game, no rewards) this can race ahead of the player's win/lose-
  screen click reaching `GameEnd()`. Since this was the FIRST line inside the method's try block,
  the exception previously aborted the ENTIRE block - shard persistence and ante handling too, not
  just the winner computation. Fixed with a null-guard (`[TFR-DuelEndRace]` logged when it fires)
  so the rest of the block still runs with `winner` defaulting false. **Reviewed adversarially
  (2026-08-13, round with the edition-restriction fixes below)** - a reviewer initially questioned
  whether defaulting `winner=false` on the null-game race could incorrectly show a "you lost"
  screen for a match whose real outcome was unknown; traced GameEnd()'s full call graph (its only
  two callers, `AdventureWinLose.actionOnQuit()` and `MatchController.finishGame()`) and refuted
  this - not a live bug.

### 53. Minimap "Temple" Icon Collision on Story Landmarks — `Fixed (playtest-confirmed 2026-08-14)`
User report + screenshots: 4 (later found to be more) locations on the minimap all show the same
"Temple"-looking icon, but each is a genuinely different location - one looked like a cave on the
overworld but had a real temple interior; the other 3 had different overworld exteriors (fort,
wizard tower, windmill/farmhouse) but all showed the same generic "Strange magical energies flow
within this place..." text. Investigated with a background workflow (3 parallel agents covering
this, the EditionProgression audit below, and its diagnostic logging). Two unrelated mechanisms
were conflated in the report: (1) the "Strange magical energies..." text + item-pair grant is a
shared, working-as-designed flavor-text template (`GameStage.effectDialog()`) that fires whenever a
dungeon's own `.tmx` map defines a `dungeonEffect` custom property - each Story location's grant is
actually fixed/distinct per map, not random; not a bug. (2) The actual icon collision is real and
fully root-caused: `World.java`'s world-gen code bakes each POI's minimap marker into the
persistent biome-image texture purely by looking up the POI's coarse `type` field in
`common/sprites/map_marker.atlas` - never its `sprite`, `name`, or tags. Every mod-added unique
"Story"-tagged landmark (Tarnation, Wizard Palace, Squirrel Farm, Gitrog Bog, Church of Valgavoth,
Kenrith's Court, Eldrazi Prison) is `type="castle"` for real, unrelated gameplay reasons (wider
vision radius, Castle music track), and the atlas's "castle" region is a small grey chapel-shaped
icon that reads as a temple - so all 7 baked the identical marker. This survived two prior
"Duplicate castle/temple icon" fix rounds (2026-08-11, Eldrazi Prison and Kenrith's Court) because
those only swapped the affected POI's overworld `sprite`, deliberately leaving `type="castle"`
untouched - neither one touched this marker-lookup code path. Fixed with a new
`World.mapMarkerKey()` helper: Story-tagged POIs use the existing "dungeon" marker instead (no new
art, per user decision), while `type` itself stays "castle" so vision-radius/music stay intact;
called from both marker-drawing sites (`generateNew()`'s POI-placement loop and
`redrawAllPoiMarkers()`, used by Territory Control redraws). Per user decision: reuses an existing
marker rather than new pixel art, and only applies to newly-generated worlds (an already-affected
save keeps its old baked icons - the marker is baked once into the persisted biomeImage Pixmap at
world-gen time, not redrawn per-frame). **Caught and fixed by adversarial review before deploy**:
the first version keyed off the "Story" tag alone, which also incorrectly caught the player's own
starting town "Spawn" (`type="town"`, also Story-tagged - would have lost its town icon), 9
cave-type Story POIs (Omenport, Three Tree City, Valor's Reach Arena, Court of Paliano, the 5
Classroom POIs - harmless size-wise but still an unintended remap), and the 5 Chapter-1-Boss
castles (Black/Blue/Green/Red/White Castle, additionally tagged "Boss"/"Chapter1Boss" - would have
downgraded their large 32x32 castle icon to the small 16x16 dungeon icon). Narrowed to only remap
when a POI is BOTH `type="castle"` AND not tagged "Boss", matching exactly the 7 originally-reported
landmark POIs. Not yet playtested - needs a NEW game (not the current save) to confirm the 7
landmarks now show the dungeon icon and Spawn/the 5 boss castles are unaffected.

### 54. "Mysterious Mage Not Found" Warnings — `Fixed (playtest-confirmed 2026-09-01)`
Found via direct forge.log review (not a user report) while investigating #53 above - the same 4+
Story `.tmx` maps (Tarnation, Gitrog Bog, Squirrel Farm, Wizard Palace) each have a spawn-point
object property `enemy="Mysterious Mage"`, but that enemy was only ever defined in a DIFFERENT
plane's data (`Realm of Legends/world/enemies.json`), not this plane's own
`The Forsaken Realms/world/enemies.json` - so every time a player entered one of these maps, the
game logged "Enemy 'Mysterious Mage' not found, choosing a random one for current biome" and
silently substituted a random encounter instead. Harmless (graceful fallback, no crash) but not
what these Story locations were built to grant. Fixed by porting the enemy definition
(`spawnRate: 0` - never randomly spawns, only reachable via these maps' own explicit `enemy`
property references) into this plane's own `enemies.json`, plus copying its small "Mystery List"
deck file (a 1-card `The Prismatic Piper` Commander deck - a build-around commander whose 99-card
deck works with any 99 Wastes, so it needed no per-plane customization) into this plane's own
`decks/legends/` folder to keep the mod self-contained. The referenced sprite atlas
(`common/sprites/enemy/humanoid/conjurer.atlas`) was already shared, no copy needed. Per user
decision. Not yet playtested - needs a fresh visit to one of the 4 locations to confirm "Mysterious
Mage" now appears instead of a random substitute, and that the "not found" warning no longer
appears in forge.log.

### 55. Progressive Set Unlocks: Colored-Booster Bypass + Stale Shop Restriction — `Done (playtest-confirmed 2026-08-18)`
User report: "I can definitely tell you that my own shops were not gated. I should only have 'New
Phyrexia' playing on Insane, yet my card-shop had a lot of expansions" - plus a design recap the
user asked to have verified ("my own shops sell only researched expansions; Inns get
player-unlocked+neutral; AI shops/Inns/monster-drops stay fixed to their world-gen-assigned
edition"). Investigated with the same background workflow as #53. Verdict: the design as coded
matches 3 of the user's 4 points correctly (confirmed via `[TFR-ShopEditions]`/`[TFR-EditionShard]`
log evidence showing the player's own shop DID correctly compute `owner=player-unlocked
restriction(1)=[NPH]` on Insane) - the one correction: **Inn tournaments are not gated per
town-color at all**, unlike shops/loot - `EditionProgression.eventAllowedEditionCodes()` computes a
single GLOBAL player-unlocked+neutral set with no town/color parameter, identical everywhere,
regardless of which town's Inn is visited. Also: the single Insane-difficulty starting edition is
NOT a color assignment - `AdventurePlayer.create()` picks it from the chosen RACE's 4-set lore pool
(e.g. Phyrexian -> `[SOM,MBS,NPH,ONE]`), so New Phyrexia was a 1-in-4 race-pool roll, not a
color-territory assignment; this pool is completely independent from the 6-way
`World.colorEditionShards` split that governs AI-town shops/monster loot/the Inn neutral slice.
Two real, independent bugs were found and fixed to explain the actual screenshot:
- **Colored-booster shops bypassed edition restriction unconditionally.** `RewardData.generate()`'s
  `cardPackShop` case has two branches - the `colors==null` branch correctly filters by
  `this.editions`, but the `colors!=null` branch (used by every White/Blue/Black/Red/Green/Colorless
  Booster shop in shops.json) called `AdventureEventController.generateBoosterByColor(color)`,
  which pulls from the ENTIRE card database with zero edition filtering, regardless of town
  ownership or the player's own unlocked editions - on every playthrough, at every shop stocking a
  colored booster. Fixed with a new `generateBoosterByColor(color, restrictEditions)` overload that
  builds its own `BoosterPack`/`SealedTemplate` (mirroring `BoosterPack.fromColor()`'s exact slot
  layout) with a `fromSets(...)` predicate clause appended per slot - a pre-existing stock
  `BoosterGenerator` operator, not a new mechanism.
- **A shop's reward pool is generated once, at whatever moment `MapStage` first builds that shop's
  actor - which for any wasteland-origin town is necessarily BEFORE the player has restored/rebuilt
  it.** Nothing ever re-generated that pool when the town-restored or shop-rebuilt quest flags later
  got set, so a freshly-claimed shop kept showing the AI-color/neutral shard it was born with
  instead of the player's own unlocked editions, until the player happened to leave/re-enter the
  town or pay for an unrelated restock/reroll. Fixed with a new `MapStage.refreshAllShopRewards(trigger)`
  that re-derives every shop's rewards using its existing seed (not a free reroll), wired into the
  "yes"/"repair" actions of all 4 restoration/rebuild dialogs via a new
  `DialogData.ActionData.refreshShopRewardsTrigger` field.

**Caught and fixed by adversarial review before deploy** (2 separate rounds):
- **Blocking**: the `fromSets(...)` clause was built without the leading quote
  `BoosterGenerator.buildExtraPredicate()`'s parser expects (matching its one other caller,
  `QuestUtilCards.java`) - the parser's `substring(...+1)` skip is calibrated to also consume that
  quote, so omitting it silently truncated the FIRST character of the first edition code in the
  restriction (e.g. "ONE" -> "NE", matching zero cards) - with a single-edition restriction (the
  realistic Insane-difficulty case) this made every slot of the booster unsatisfiable, silently
  awarding a 0-card booster with no error. Fixed by quoting each code, matching the established
  convention exactly.
- **Moderate**: the `EconomyBuildings.buildOption(NONE, objectId)` "Card Shop" plain-rebuild
  option - the single most common wasteland-shop-rebuild path, and the only one repeatable via the
  existing "Destroy Building" feature - was missed from the refresh wiring; fixed by adding it
  there too.
- **Minor**: `refreshAllShopRewards()` originally hardcoded a single `"restoration"` trigger label
  regardless of which of the 4 dialogs actually fired it, making the diagnostic log unable to
  distinguish them; changed to thread a specific trigger string through each call site
  (`town-restore`/`shop-rebuild`/`shop-repair`) instead.
Not yet playtested - needs the user to confirm a colored-booster shop only offers cards from
unlocked editions, and that restoring/rebuilding a shop immediately reflects current research
without needing to leave and re-enter the town.

### 56. Diagnostic Logging for Edition-Restriction Decisions — `Done (playtest-confirmed 2026-08-22)`
User request, following up on #55's investigation: "It's hard for me to test what the other AI
colors should or should not have... can we somehow create a log for future testing." Audited all 3
edition-restriction decision points (shops, monster loot, Inn tournaments) for existing coverage
under this project's standing `[TFR-<Name>]` diagnostic-logging practice. `[TFR-ShopEditions]`
(shops) and `[TFR-LootEditions]` (monster loot) already existed and covered both player AND every
AI-color town correctly, but had gaps; Inn tournaments had NO tag at all. Extended/added:
- `[TFR-ShopEditions]` (`EditionProgression.restrictShopRewardsForCurrentTown()`) gained a `trigger`
  label per call site (`init`/`restock`/`armory-reroll`/`armory-upgrade`/`shop-reroll`/
  `town-restore`/`shop-rebuild`/`shop-repair`, replacing a hardcoded `(regen)` suffix that couldn't
  distinguish first-ever map-load generation from a player-triggered regeneration), plus the actual
  town/POI name and a `reason` field (`capitol`/`restored`/`color=<X>`/`no-match-neutral`) so the
  branch taken is independently verifiable from the log alone, not just the final owner label.
- `[TFR-LootEditions]` (`EnemySprite.java`) gained an `EXEMPT` line for the boss/quest-tagged
  exemption case, which previously fired completely silently - "exempted by design" was
  indistinguishable from "this code path never ran" when grepping for a specific enemy.
- New tag `[TFR-InnEditions]` (`AdventureEventData.java`, `pickWeightedCardBlock()`/
  `pickJumpstartCardBlock()`) - Inn tournament edition selection had no logging at all before this.
  Important scoping note surfaced by this work: Inn editions are NOT per-AI-color the way
  shops/loot are (see #55) - this log line reflects one global player-unlocked+neutral-shard result
  per event roll, not a per-color breakdown.
**Caught and fixed by adversarial review before deploy** (minor): the new town-name null-guard on
`[TFR-ShopEditions]`'s log line only protected the log-construction itself: the color-match branch's
own town-color lookup dereferenced the same POI reference five lines earlier, unguarded - so the
guard could never have actually helped if that scenario ever occurred (not currently reachable at
any of the method's 6 call sites, but a real latent inconsistency). Fixed by reading the POI
reference once, guarded, and reusing it consistently through the whole method. Not yet playtested -
these are diagnostic-only additions with no gameplay effect; "testing" here just means confirming
the new log lines appear correctly formatted in forge.log during normal play.

### 57. Deck Tester: 50x Speed, "AI vs. AI - No Watch" Batch Mode, Mode Rename — `Done (playtest-confirmed 2026-08-14)`
User request, after trying the AI-vs-AI "Watch" mode from #52 ("worked great"): (1) add a 50x
option next to the existing "10x speed" spectator button; (2) rename "Coin Flip" to "Player vs.
AI"; (3) split the AI-vs-AI mode into two - "AI vs. AI - Watch" (today's mode, just renamed) and a
new "AI vs. AI - No Watch", which asks how many matches (5/10/20) and runs them all in the
background, as fast as possible, with no visible duel, reporting only the final win/loss tally per
deck. Researched first (background workflow) whether a true headless simulation path already
existed on Adventure's classpath - it didn't: `forge-gui-desktop`'s `SimulateMatch.simulateSingleMatch()`
uses exactly the right pattern (`Match.createGame()`/`Match.startGame()`, forge-game's engine with
zero GUI coupling) but lives in a module `forge-gui-mobile` doesn't depend on. Also confirmed the
existing "Watch" mode is inherently NOT headless - it always routes through `HostedMatch`'s
`humanCount==0` spectator path (`WatchLocalGame` + `FControlGamePlayback`), which unconditionally
pays a UI-pacing tax (artificial `Thread.sleep`s between game events) even with no screen ever
shown - so "No Watch" needed genuinely new plumbing, not a hidden reuse of "Watch".

- **50x speed**: `PlaybackSpeed.java` (a shared/global spectator-pacing enum used by ALL of Forge's
  match-watching, not Adventure-specific) gained a new `SUPERFAST(.02)` tier inserted into the
  existing `NORMAL->FAST->SLOW->NORMAL` cycle (now `NORMAL->FAST->SUPERFAST->SLOW->NORMAL`). A
  pre-existing animation-skip check in `FCardPanel.java` (`== PlaybackSpeed.FAST`) was extended to
  also skip at `SUPERFAST` - the fastest tier should skip at least as much as FAST, not less.
- **"AI vs. AI - No Watch" batch mode**: new `forge/adventure/util/DeckTesterSimulator.java` -
  bypasses `HostedMatch`/`MatchController`/`DuelScene` entirely, driving forge-game's `Match`/`Game`
  engine directly on a background thread (mirroring `SimulateMatch`'s own pattern, reimplemented
  locally rather than reused since it's not on this module's classpath), looped N times, with a
  per-game timeout so one stuck AI game can't hang the whole batch. By user decision (asked
  directly, since this changes what "who won" even measures): matches are a pure, symmetric
  deck-vs-deck test - both sides use the engine's ordinary default starting life/hand, no player
  equipped-item/blessing effects, no difficulty scaling, no ante - unlike "Watch" mode, which treats
  one seat as "the player" (real life/shards/items) and the other as "the enemy"
  (difficulty-scaled life). `ArenaScene.java`'s Deck Tester flow restructured: the boolean
  `simulated` parameter became a 3-value `DeckTesterMode` enum (`PLAYER_VS_AI`/`AI_VS_AI_WATCH`/
  `AI_VS_AI_NO_WATCH`), a new match-count dialog (5/10/20) follows deck selection for the No-Watch
  path, and a live-updating progress dialog + final tally dialog replace the usual scene-switch
  for the duration.
- **Rename**: "Coin Flip (you pilot one deck)" -> "Player vs. AI"; "Simulated (AI vs AI)" -> "AI vs.
  AI - Watch".

**Caught and fixed by adversarial review before deploy (blocking)**: the first version of
`DeckTesterSimulator` only wrapped the actual game-execution call in try/catch - per-game SETUP
(`RegisteredPlayer.forVariants`/`new Match`/`createGame`/`getWinner`) and the pre-loop AI-player
creation were unprotected. Since this headless path bypasses whatever deck-legality checks a normal
`GameLobby`/`HostedMatch` flow applies, any exception there would have killed the background thread
before it ever reached the completion callback - and since that callback is the ONLY place
`ArenaScene.enable` gets reset back to `true` for this flow, that would have permanently soft-locked
the entire Arena screen (no exit, no restart, an undismissable "Simulating matches..." dialog with
no buttons) until the app was killed and restarted. Fixed with two layers: an inner per-game catch
so one bad game counts as a draw and the batch continues, and an outer try/finally that guarantees
the completion callback ALWAYS fires no matter what throws, so the UI can never get stuck waiting on
it. Both catches also match `SimulateMatch`'s own `Exception | StackOverflowError` precedent for
this style of loop (deliberately not a blanket `Throwable` catch, so a genuinely fatal `Error` still
propagates). Not yet playtested - needs a 50x-speed watch, and a No-Watch batch run (ideally
including a case that would previously have hit the fixed exception-handling gap) to confirm the
tally and that the Arena screen stays usable afterward.

**Follow-up (2026-08-13, user report): the adversarial-review fix above wasn't actually enough -
a real freeze still happened in play.** The per-game catch/finally layers above only ever protected
against *exceptions*; they did nothing for a plain *hang* (no throw, just blocked forever), and
`createGame()` was still being called synchronously on the batch thread, outside the timeout-
protected executor entirely - so a hang there froze the whole batch with no timeout and no error,
exactly what the user hit. Confirmed via `forge.log`: games 1-3 completed/timed out normally, then
total silence, no "batch aborted" line ever printed. Fixed by moving `createGame()` inside the same
per-game executor `startGame()` already used, and replacing the single blocking `future.get(90s)`
call with a 500ms polling loop against the real deadline. Same round added the requested "End Test"
button (`DeckTesterSimulator.runBatch()` now returns a cancellable `Handle`, polled every 500ms so
cancellation lands within half a second even mid-game). See `MOD_CHANGELOG.md`'s 2026-08-13 "Deck
Tester freeze fix..." entry for the full writeup. Still not yet playtested against a live freeze
repro.

### 58. Grandmaster Tier Rename + Tiered Enemy Display Names — `Done (playtest-confirmed 2026-08-14)`
User spec: the enemy tier ladder's display convention is now Apprentice → Adept → Master →
**Grandmaster** (top tier renamed from "Challenger"), and every enemy shows its tier appended to
its displayed name, e.g. **"Red Wizard (Adept)"**. Display-only throughout: internal tier strings
stay Common/Uncommon/Rare/Mythic, no enemies.json names changed, and the new name suffix is gated
on a new `showEnemyTierInName` config flag (on only for this plane). The tiered wizards' names
already carry their tier as a prefix ("Adept Red Wizard") - a prefix matching the enemy's own tier
is stripped so the display is "Red Wizard (Adept)", not doubled. Shown on vs-transition screens,
the in-duel opponent nameplate, and boss intro/loss dialogs; Inn-tournament event opponents stay
raw (their standings/bracket screens use raw names). **Quest safety confirmed before building**
(user asked): quest Defeat matching runs on `EnemyData.match()` - raw name + questTags - and every
other identity path (.tmx enemy refs, `WorldData.getEnemy()`, deck-number keys) also uses raw
names, all untouched. Known cosmetic quirk left for user decision: the 3 Arena champions
(nameOverride "Challenger", tier Mythic) display "Challenger (Grandmaster)" - their names were
never tier labels, so they were deliberately not renamed. Same round also fixed 15
holistic-review findings across the last 2 days' work - see MOD_CHANGELOG.md's "2026-08-13 (late
night 2)" entry for the full list (dungeon-chest edition-theme clobbering + wasteland bypass, Deck
Tester End-Test tally/thread-leak/statistics-pollution, a latent Commander-removal NPE, the
"edition status" stale-POI readout, a pre-existing roaming-champion save/load collision, a stale
enemies.csv, and date-label corrections).

### 59. Playtest Round: FoW Threshold Fix, Castle Strength, Printing Remap, Renames, Icons, Shops — `Done (playtest-confirmed 2026-08-18)`
Ten-workstream round from a single extended playtest session, covering a log review, a
user-diagnosed fog-of-war bug, a feature request, a design-verification screenshot audit, and
several smaller reports. Full detail in MOD_CHANGELOG.md's "2026-08-13 (late night 3)" entry - this
is a summary index:
- **Log review**: clean (0 exceptions in 64k lines); found and fixed `[TFR-Intrusion]` running
  every frame instead of once per spawn (89.5% of the session's log was this one bug).
- **Fully-explored fired too early** (user correctly diagnosed the cause: the Capitol's territory
  radius grew to its 450 max even after real ownership stalled far short of it, and the reveal
  covered the whole geometric disc - ocean included). Fixed at the source (radius only advances on
  real claims, reveals only claimed ground, 80% threshold measured over land only) plus a new `fog
  reset` console command to repair the user's existing save.
- **AI castles strengthened** per user request - a dedicated pull-weight/hard-protect buff for the
  5 AI castles only, feeding the existing daily-expansion contest system.
- **Progressive Set Unlocks printing fix** - a screenshot audit (user request, verifying 3 shop
  folders against logged edition-shard data) found the shard partition itself is correct at the
  card-NAME level, but ~54 items showed out-of-shard PRINTINGS of otherwise-legal cards, which also
  silently broke research-progress crediting. Fixed with a printing remap at generation time.
- **Data/content**: 46 items with broken icons restored (user found 2, full audit found 46);
  Challenge Coins removed from the Armory pool again (reinstated by an earlier catalog rework);
  teleport runes restored to the 5 AI-capital specialty shops (user report - removed by an earlier
  round's over-broad purge); the 3 Arena champions renamed from generic "Challenger" to distinct
  MTG lore names (Haktos/Phage/Ixidor) at the user's request; Capitol minimap icon now uses its own
  sprite; give wood/stone console commands now play a sound.
- **Caught and fixed by adversarial review before deploy** (3 of 4 candidates confirmed, one
  blocking): an initial version of the castle-strength fix also applied the new exclusion zone to
  town capture/restore repaints, which desynced a town's recorded territory radius from what was
  actually painted whenever captured/restored within ~22 tiles of a rival castle - permanently,
  with no self-heal. Fully reverted that companion mechanism rather than patching it; the intended
  castle-strength effect survives entirely through the existing daily-expansion contest system.
  Also fixed: a land-tile-count cache that never reset across the `World` singleton's reuse between
  games in one session, and unbounded `[TFR-PrintRemap]` log spam from stable per-shop seeds
  re-logging identical remaps on every town re-entry.
Not yet playtested - the user's current save needs `fog reset` run to clear its existing
over-revealed fog; everything else needs a fresh look in-game.

### 60. Territory Pacing, Guard Info, Dialog Text Wrap, Spellsmith Editions, AI Mage Tier Variety — `Done (playtest-confirmed 2026-08-18)`
Six items from one round - the 6th (AI mage tier variety) was held for a user clarification before
building (no matching "Grandmaster"-tier wizard enemy exists per color, so a literal reading of the
requested odds couldn't always resolve to something), then built once the user confirmed a direction.

- **AI-dispatched mage tier variety.** Was hardcoded to always dispatch "Adept &lt;Color&gt; Wizard" -
  every attack, every color, forever (confirmed by direct code read: one call site, zero variation
  by rank/difficulty/day/anything). Now a weighted roll: Apprentice 30% / Adept 50% / Master 15% /
  Grandmaster 5% (user's exact odds), same cumulative-boundary pattern `RewardData.
  rollWeightedItemRarity()` already established. Apprentice/Adept/Master still use the real named
  wizard for that color (unchanged behavior for those 3 tiers). Grandmaster has no named wizard for
  any color, confirmed - per user decision, picks randomly from that color's own Mythic-tier roaming
  pool instead of inventing a stand-in (17-26 real candidates per color, confirmed directly against
  each color's own biome file), excluding bosses/quest-tagged enemies (same exclusion
  `EnemySprite.getRewards()` already uses for its own edition-restriction exemption) - a real,
  already-established threat for that color, not literally named "Wizard" but not arbitrary either.
- **Rebuild/repair dialog button text wrapping** (e.g. "Rebuild Arena (250 [+Gold])" wrapping with
  the icon alone on its own line): fixed at the shared `MapDialog` option-button renderer (a `[%88]`
  scale prefix) rather than Arena-specifically - every DialogData-driven dialog in the mod (shop
  rebuild/repair, quest choices, etc.) uses this same renderer, so this is a small universal buffer
  against marginal-length labels. Wrapping itself is untouched, still there for genuinely long text.
- **Capitol territory growth: 9 tiles/day -> 1 tile/day.** `TerritoryControl.java`'s own long-standing
  comment on the shared constant already flagged this exact intent ("3 -> 9... TEMPORARY testing
  pace... the user intends to drop this to 1 tile/day or slower for the real slow-burn pacing") -
  this round finally splits it off into its own `CAPITOL_EXPANSION_TILES_PER_DAY` constant. AI
  castles keep the 9-tiles/day pace (not requested to change).
- **Ordinary-town territory growth: 9 tiles/day -> 1 tile/week.** A per-day rate can't express
  "1 tile per 7 days" as a whole number, so this needed real day-tracking, not just a smaller
  multiplier - new `World.townLastGrowthDay` (per-town, same persistence pattern as
  `townTerritoryRadius`) tracks each town's own last-grew day, mirroring the exact "accumulate
  until a threshold, advance in whole steps" shape guard salary's `lastPaidDay` already uses.
  Applies uniformly to both player- and AI-owned ordinary towns (the growth mechanism was already
  shared between them). A blocked growth attempt (fully contested land) keeps its earned tile(s)
  banked and retries next tick rather than losing progress to the temporary block.
- **Guard hiring "Info" button**: a new dialog explaining the whole mechanic in the player's own
  terms - guard counts per town/Capitol, weekly costs per tier, how a guard fight's odds are
  computed (tier matchup + the real +10%/-5% Outlook adjustment), what happens on a guard loss
  (mage moves to the next guard or the town itself) vs. a full defense (attack never reaches the
  town), the underlying town-capture roll odds by attacker tier once all guards fall, the 20% sack
  chance, and the Capitol's own added stakes (clearing its 2 guards + winning that roll triggers the
  forced defense duel, not the ordinary capture - losing it ends the run). Numbers are literal, not
  read live from `TerritoryControl`'s private constants (same reason the pre-existing Outlook info
  dialog does this too) - flagged with a comment to keep in sync if those ever change.
- **AI-capital Spellsmith showing the wrong edition pool + no reputation gate.** Two real bugs, one
  user report: the Spellsmith at every town (including AI capitals) was filtering its stock to the
  PLAYER's own unlocked editions regardless of whose capital it was - fixed to use that color's own
  dealt 1/5 shard instead (`EditionProgression.getEditionsForColor()`, the exact same helper/branch
  logic `restrictShopRewardsForCurrentTown()` already established for card shops), with player-owned
  towns/the Capitol keeping the player's own unlocked editions as before. Second: nothing gated
  *access* to an AI-color Spellsmith at all - new `ColorReputation.isSpellsmithAccessible()`
  (Happy/Partner only, deliberately stricter than the general War-only capital entry toll, and
  specific to this one building) now blocks entry with an explanatory dialog below that threshold,
  wired into `MapStage.java`'s "spellsmith" collision case.

Not yet playtested - needs the user to see the button-text fix, watch both growth rates over
several in-game days/weeks, read the new Guard Info dialog, check a low-reputation AI capital's
Spellsmith (blocked entry) against a Happy/Partner one (right edition pool, not the player's own),
and watch enough mage dispatches to confirm the tier mix (`[TFR-...]` isn't tagged for this yet -
plain `[TerritoryControl]` log lines show "dispatch rolled tier X (Y) -> Z" per dispatch).

### 61. Color Defeat Endgame, Player-Biome Spawn Variety, Dead Enemy-Reference Fixes — `Done (playtest-confirmed 2026-08-18)`
Three items from one round, the third an audit finding from the previous conversation.

- **Color Defeat (the big one, user's "next big, endgame update").** Beating one of the 5 colored
  castles now genuinely defeats that color, not just clears a quest checkbox:
  - **Full terrain revert**: every tile that color owns ANYWHERE on the map (not just near its
    castle) reverts to neutral wasteland - reuses the exact primitive world-gen already trusts for
    "sweep everything a color owns" (`World.neutralizeTerritoryOutsideRadius()`, called with radius
    0 instead of the world-gen keep-circle radius). Every town and its capital converts to the
    normal broken-town ruin state (`TownRestoration`'s existing mechanic), the same way a captured
    town reverting to neutral already looks. In effect this stops all of that color's roaming
    spawns (biome ownership is what `WorldStage` roams from) and re-opens any of its
    terrain-specific dungeons to reappear as neutral/wasteland content once they next time out -
    both fall out naturally from the terrain flip, no separate code needed (confirmed both
    mechanisms already key off live biome ownership).
  - **Reputation: flat -50** to the defeated color, deliberately breaking the reputation system's
    usual net-zero invariant (a color being wiped isn't a duel event) - the other 4 colors' tracks
    are untouched and the whole system keeps working normally per the user's explicit request to
    keep reputation live for all 5 colors regardless of defeat status.
  - **+1 attacking-mage slot for every surviving color, per additional color defeated** (user's
    pick: stacks, not a flat one-time bonus - "consistent with... escalating pressure as the world
    shrinks").
  - **Attacker tier distribution shifts, also stacking per defeat.** Real finding from this round:
    the "AI-dispatched mage tier variety" feature (pulled in from the other machine this same
    session, previously believed by the user to already include this) is a flat Apprentice 30% /
    Adept 50% / Master 15% / Grandmaster 5% roll with NO scaling by anything - the endgame shift
    IS the first thing that ever varies it. Per defeated color: Adept -10 / Master +5 / Grandmaster
    +5 (Apprentice untouched), clamped so Adept can't go negative - lands exactly on 0 at 5 defeats.
  - **The 2 colors adjacent to the defeated one on the color wheel** (its allies, per the existing
    ally table - confirmed matches the user's own Green-defeated -> White/Red worked example
    exactly) each get a ONE-SHOT "next attack is guaranteed to target a player town" flag, consumed
    on that color's next dispatch. If the player owns nothing yet when that dispatch would fire,
    the flag stays armed for a later one instead of being wasted on an impossible forced pick.
  - **Real trigger**: hooked at `AdventurePlayer.setQuestFlag()` (corrected mid-round - see the
    adversarial-review note below; originally miswired to the similarly-named but unrelated
    `MapStage.setQuestFlag()`), the actual call site every castle's boss-defeat dialog action fires
    (`{"setQuestFlag":{"key":"Ch1BlackCastleComplete","val":1}}`, confirmed by reading the .tmx
    directly) - independent of whether the "Rescue the Captive" quest STAGE that also reads this
    flag completes correctly (a separate, pre-existing quest-system detail this feature doesn't
    depend on).
  - **Testing tool, TEMPORARY - remove once playtested**: new console command
    `defeat castle <color>` fires the exact same consequence without needing to clear one of the 5
    (very difficult) castle bosses first - writes the real quest flag too, as close to "complete
    the quest" as a console command gets.
- **Player-biome spawn variety** (user request, same conversation as the audit below): player
  territory's roaming roster (72 enemies, #19's own "feels dead" gap only partially closed) now has
  a small 8% chance per spawn roll to pull from the colorless/Wasteland roster instead - independent
  of the existing foreign-color-intrusion mechanism (no proximity/reputation gating, just more
  variety on the player's own land). Tunable constant, flagged for the user to adjust if it feels
  off in either direction.
- **8 dead enemy-name references fixed**, found while answering a user question about enemy tier
  counts: `Kobold` (no bare entry exists, only `Kobold Warrior` etc. - fixed to that), `Angelic
  Overseer` in white.json/blue.json (real entry is `Angel Overseer`), `Ibis-headed Aven Initiate`
  in blue.json (real entry is `Ibis-headed Aven Warrior`), and 5 `<Color> Sliver` entries across
  blue/black/white/green/red.json (real per-color entries use the `Sliver_<Color>` naming
  convention already established elsewhere in the same files). All 8 were silently unresolvable
  spawns before this fix.

**Adversarial review found and fixed 9 real issues (2026-08-14), including one BLOCKING one**: the
feature's real in-game trigger was wired to the wrong method (`MapStage.setQuestFlag()` instead of
`AdventurePlayer.setQuestFlag()`, two confusingly-similarly-named methods backing two different
dialog-action JSON keys) - an actual castle boss kill would never have fired any of this. Also fixed:
a defeated color's capital silently resurrecting on every save reload, a doodad-placement call that
re-randomized unrelated already-settled ground on each additional defeat, the player's Capitol being
double-weighted in the forced-target pick, plus 3 minor lifecycle/log-spam gaps and 2 nitpicks. Full
list in MOD_CHANGELOG.md's "Adversarial review" entry. All fixed, recompiled, redeployed.

**Real 2-color playtest since confirmed the consequence logic works** (terrain sweeps, mage-cap
+1/+2 stacking, forced-ally-targeting all firing correctly with clean logs) - but surfaced ONE more
real bug the code review missed: mages dispatched BEFORE their color's defeat stayed in flight and
could still capture towns (or worse, trigger the Capitol's run-ending duel) AFTER the defeat -
`[TerritoryControl] ... has fallen to White!`/`...to Black!` both appeared in `forge.log` AFTER
their color's own `DEFEATED` line. User independently caught the same bug live ("I killed black,
then a black mage that was still on-route captured a town"). **Fixed**: `onMageArrived()` now
checks `isColorDefeated()` first and fizzles silently if true - full writeup in MOD_CHANGELOG.md's
"10th finding" entry. Recompiled, redeployed.

### 62. Playtest Round: SpellSmith Editions, Arena Text, Torch Redesign #2, Armory Odds, Guard Dialog, Shop-Reroll Booster Split, Grand Torch — `Done (playtest-confirmed 2026-08-18)`
Six playtest reports plus four scope-confirmation asks from the same session as #61, investigated
via 6 parallel read-only Explore agents before any code changed. Full technical detail in
MOD_CHANGELOG.md's "Second-half round" entry - summary here:

- **SpellSmith editions bug, real root cause found**: two independent, never-synced filter code
  paths in `SpellSmithScene.java` - the underlying card pool was correctly branched to the
  town-color's shard, but the dropdown the player actually sees/picks from was a separate method
  that never got the same fix. Consolidated into one shared helper so they can't drift apart again.
- **Arena rebuild-dialog text wrap**: the cost figure is dropped from the shared `TownRestoration.
  buildRebuildShopDialog()` body text (used by Arena/Spellsmith/Shard Trader alike) since the
  button right below it already shows the cost.
- **Guaranteed-Torch redesigned a 2nd time** (see #51) - now genuinely "guaranteed purchasable,"
  not free-to-inventory, without reintroducing the staleness bug the 1st stock-seeding attempt hit.
- **3 (really 4) Armory items were hardcoded-guaranteed** ("Staff of Healing/Flight/Speed",
  "Manasight Stone") instead of rolling normal rarity odds - root cause was a `questItem` flag that
  incidentally also hid them from the normal weighted pool, forcing the hardcoding in the first
  place. Fixed at the data level; total Armory stock size (6 at L1, 8 at L2, per #47) preserved.
- **Guards dialog UI**: Info/Close buttons shrunk and placed side-by-side; the Info popup's
  overflowing text wrapped in a scrollable pane.
- **Shop-type re-roll (#32) audited and a real gap fixed**: Booster shops were mixed into the same
  reroll pool as ordinary card shops in the 5 AI-capital towns - now kept strictly separate.
  (Also confirmed: 280 total shop templates, 51 structurally unreachable via reroll regardless -
  all pre-existing unused/placeholder content, not a reroll bug.)
- **New item, Grand Torch** (Rare, 1000g, same slot as Torch, `visionRadiusMultiplier: 4.0`) -
  user-supplied art, processed the same way the original Torch's was. Original Torch's own
  multiplier reduced 3.0x -> 2.0x so Grand Torch is a genuine upgrade, not a sidegrade.
- **#17 Territory Effects confirmed NOT built** - the "faster on own land, slower in hostile land"
  speed effect the user believed was already done isn't in the code anywhere (direct search of
  every movement-speed call site in the repo). `MOD_SCOPE.md`'s own "candidate, none committed"
  status for that bullet was accurate, not stale - would need real scoping/building if still wanted.
- **#29 (Extra Attacking Mage per 10 Player Cities) already has adequate logging** - `[TFR-MageCap]`
  prints `playerTowns`/`divisor`/`townBonus`/`cap` on every dispatch cap-check; no new logging added.

Not yet playtested - needs the user to see each fix in-game (SpellSmith edition list at an AI town,
Arena repair text, Guards dialog layout/scroll, a fresh Armory's item variety, a shop reroll staying
within its own booster/non-booster category) and confirm the Grand Torch's art/stats read correctly.

**Still not fully playtested end-to-end** - the corrected real trigger (`AdventurePlayer.
setQuestFlag()`) and a genuine castle boss kill specifically have not yet been tried against the
now-doubly-fixed build (hook + in-flight-mage fizzle both landed after the only real playtest so
far). Everything else - terrain/town revert, reputation -50, mage-cap/tier-shift stacking,
forced-ally-targeting - has real playtest confirmation.

### 63. Territory Speed Numbers, Reroll Pricing Redesign, New Tuning Config, Info-Page/Minimap Overhaul — `Done (playtest-confirmed 2026-08-18)`
Fourteen items from one large round: concrete numbers for #17's speed effect, a town-radius bump,
a Capitol shop-coverage audit, a reroll-pricing redesign, a new numeric-tuning config file (plus an
AI-castle-speed regression fix), a UI label rename, a Capitol minimap name-collision fix, a World
Standings coloring swap, the Armory/Guard Info page's scrolling finally fixed for real, a new "Mod
Details" overview page, a town-count bar chart, and a minimap-button caption/behavior fix with a new
"towns under attack" overlay.

- **#17 Territory Effects, first real numbers** (`WorldStage.territorySpeedModifier()`): player's
  own land is always +15% move speed. AI-color land scales with `ColorReputation` status with that
  specific color - Partner +10%, Happy +5%, Unhappy -5%, War -10%, Neutral no effect. Multiplicative
  with the existing sprint modifier; deliberately does NOT apply on roads (the road branch has no
  cheap "whose biome is this" lookup without unmasking the road bit, judged out of scope). All 5
  percentages are tunable (see the new config file below), not hardcoded. Change-triggered diagnostic
  logging (`[TFR-TerritorySpeed]`) logs once per biome-name change, not every frame.
- **Town max territory radius: 15 -> 20** (`TuningData.townMaxTerritoryRadius`).
- **Capitol shop-type coverage audited, a real design gap found (not fixed, needs a decision)**: all
  12 of the Capitol's ordinary card shops share byte-for-byte IDENTICAL commonShopList/uncommonShopList/
  rareShopList/mythicShopList `.tmx` properties - rerolling any of them draws from the same pool, so
  reroll produces no real per-shop variety at the Capitol specifically (works fine at ordinary towns).
  The much larger AI-capital-flavored shop-type roster (~150+ tribal/color-specific names) is
  structurally unreachable there regardless of rerolling. Fixing this would mean hand-authoring 12
  `.tmx` object properties with distinct candidate pools - a real content task, not a code fix, and
  not done this round pending a decision on whether it's worth it.
- **Reroll pricing redesigned for both reroll systems** (card-shop type reroll AND Armory inventory
  reroll): the old hard "once per 7 days" cooldown is gone, replaced with an escalating shard
  surcharge - +1 shard per reroll already used since the last CALENDAR-WEEK boundary (`day / 7`
  integer division, the exact same "which week" math the pre-existing Guard weekly-pay formula
  already used), resetting to the base cost at each week boundary. Example: base cost +4, three
  rerolls this week makes the next one +7; a new week resets back to +4. New `PointOfInterestChanges.
  rerollSurcharge()`/`recordReroll()`, `shopRerollCountThisWeek` field (persisted - see below).
- **New tunable-numbers config file**: `tuning.json` (`TuningData.java`, loaded via
  `Config.getTuningData()`, same plane-local-with-common-fallback resolution as every other
  plane-scoped file) holds day length, Capitol/town/AI-castle expansion rates, max territory radii,
  the Speed-Up multiplier, and all 5 territory speed percentages above - previously a mix of
  hardcoded constants scattered across `World.java`/`WorldStage.java`/`TerritoryControl.java`.
  **Found and fixed in the same pass**: AI castle expansion was still hardcoded at 9 tiles/day (the
  "TEMPORARY testing pace" #60 explicitly left unchanged at the time) - now 1 tile/day like the
  Capitol, via `TuningData.aiCastleExpansionTilesPerDay`, confirmed against the user's own log-derived
  observation ("day 40 and they were already in the middle of the map... should expand 1 tile a day
  max") rather than assumed. **Speed-Up's real default also changed 100x -> 50x** per explicit
  request, now living in `tuning.json` instead of a hardcoded constant.
- **"100x Speed" UI checkbox renamed to "Speed-Up"** (`lblFastTimeToggle`, shared `en-US.properties` -
  the number no longer belongs on the label now that the multiplier is a tunable, not a fixed value).
- **Capitol minimap name-collision fixed**: a POI's own name label and any `AdventureEventData`
  (Inn tournament) label sourced from that same POI were both centered on the identical map
  coordinate with no offset, colliding whenever both existed for one POI (user screenshot: "Bloomburrow
  (set)" overlapping "Orazca"). Fixed in `MapViewScene.details()` - event label(s) now stack
  progressively lower, one row per label already placed at that POI.
- **World Standings reputation coloring swapped**: the tier color (Partner green / Happy cyan /
  Unhappy orange / War red) now rides the STATUS WORD ("Happy"/"War"/etc.) instead of the numeric
  reputation value, which stays plain black like every other numeric column in the table - per user
  follow-up request on #1's original coloring choice.
- **Armory/Guard Info page scrolling, fixed for real this time** (see #18/#32's earlier attempt,
  which looked fixed but wasn't - see MOD_CHANGELOG for the root-cause writeup). New reusable
  `InfoTextScene` (a full `Scene` with a JSON-authored fixed-size scroll region, no `Dialog.pack()`
  anywhere in the loop - the actual bug in the earlier attempt) replaces the old broken Dialog-based
  Guard Info popup, mirroring the Inn's own working tournament-info scroll pattern per user request.
- **New "Mod Details" page** (World Standings screen, `InfoTextScene`-based, replaces nothing existing
  since no literal "Explanations" button was ever found in code - added alongside the existing
  Reputation/Expansion wiki popups instead): a comprehensive, promotional-but-technical overview for
  a player who's never touched this mod - how it differs from stock Shandalar, how sets unlock, how to
  defend territory, terrain speed bonuses/penalties, race-based starting sets, and what difficulty
  actually changes beyond monster strength.
- **World Standings blank-spot bar chart**: a live per-color town-count snapshot chart fills the
  previously-empty space on the Info page, reusing `GameHUD.getMageMarkerColor()` for the same
  per-color palette as every other map marker in the mod. Deliberately a CURRENT-state snapshot, not
  a trend line - this mod persists no town-count history anywhere, so a real trend graph would need a
  new time-series buffer first; flagged as a real prerequisite gap, not silently faked.
- **Minimap button audit**: the "Events"/"Reputation" cycling buttons were mislabeled relative to
  their own behavior (the "Events" button actually showed per-POI reputation numbers; "Reputation"
  actually showed visited dungeon/cave/castle names) - captions swapped to match ("Reputation" /
  "Landmarks") via a new plane-local `ui/map.json`/`map_portrait.json` override, leaving the shared
  `common/ui/map.json` these mislabels came from untouched for every other plane. Also added a new
  "towns under attack" overlay to the Details button per user suggestion - one label per in-flight
  Territory Control capture mage with a live target, drawn at the TARGET town's position and colored
  to match that mage's own minimap dot, reusing the same per-POI stacking-offset fix built for the
  Capitol name-collision bug above so it can't collide with existing name/event labels.
- **Persistence gap found and fixed in the same pass**: the new `shopRerollCountThisWeek` field (the
  escalating-reroll-cost counter above) had no `save()`/`load()` entries when first added - would have
  silently reset to base cost every time the game reloaded. Fixed alongside the pre-existing
  `shopManualRerollLastDay` field's own load/save entries.

**Adversarial review (4-dimension workflow, 2026-08-15) - 6/6 findings confirmed real, all fixed**:
before any playtesting, ran a workflow of independent finder agents over reroll pricing, territory
speed, the tuning config system, and the new UI additions, each finding adversarially re-verified by
a second agent trying to refute it. All 6 raised findings survived verification:
  1. `shopTypeRerollButton`'s affordability check compared shards against only the flat base cost,
     not base+surcharge - a player could see an enabled button that silently did nothing on click
     once any reroll had been used that week. Fixed, and while in there also fixed both reroll
     buttons' displayed TEXT to show the true escalated cost (previously always showed base cost).
  2. The `[TFR-TerritorySpeed]` diagnostic log only fired on a biome-NAME change, missing the case
     where a reputation-tier crossing changes the real modifier while the player stays on one
     continuously-named biome (e.g. a duel outcome shifts reputation while standing still). Fixed to
     also log on a modifier-value change.
  3. `territorySpeedModifier()` had no floor - a modder setting a penalty tunable >= 1.0 in
     `tuning.json` would drive the modifier to zero or negative, and libGDX's `Vector2.setLength()`
     both discards sign on a negative input and can't un-zero an already-zeroed vector, silently
     breaking player movement. Ships safe today (max shipped penalty 0.10), but added a 0.1x floor
     regardless so a future tuning edit can't produce an unusable result.
  4. `lastLoggedSpeedBiome` was never reset across a save load, so the first territory entered in a
     freshly loaded session could go unlogged if it happened to share a name with whatever was last
     logged before quitting. Reset added to `clearCache()` (covers save loads; a brand new game
     doesn't route through that method, left as a narrow, cosmetic-only residual gap).
  5. The new "Mod Details" button and the "Back" button overlapped in a real 55x3px hit-area region,
     with "Back" winning the overlap since it's declared later in the JSON (libGDX hit-tests last-
     added-first) - could steal clicks meant for "Mod Details". Fixed by repositioning.
  6. The new "towns under attack" overlay drew unconditionally, with no fog-of-war visibility check
     - unlike the pre-existing mage-marker loop it was modeled on, which does check. Leaked an
     unexplored town's existence/location/under-attack status through solid fog. Fixed with the same
     `isCurrentlyVisible()` gate.

Not yet playtested - needs the user to feel the territory speed changes in motion, confirm the
reroll-pricing math across a real week boundary (and after a save/reload), watch the AI castle
expansion pace over several days, read the new Mod Details page and bar chart, and try the fixed
minimap buttons and Guard Info scroll.

### 64. Playtest Round: Mod Details Crash, Reroll Pricing Correction, Info Button Move, Day/Week Tracker, Starting Resources — `Built (2026-08-15), playtest-confirmed the crash is real and fixed`
Five items from the very first real playtest of round #63's work.

- **Real crash, root-caused and fixed**: clicking "Mod Details" (or the Guards dialog's own "Info"
  button - same underlying bug, hit first, 5 times, before the reported crash) threw
  `ClassCastException: Controls$LabelFix cannot be cast to ... Label` and took the whole app down.
  Root cause: `InfoTextScene`'s `title` field was typed as libGDX's raw `Label`, but a JSON
  `"type": "Label"` element actually instantiates `Controls.newTextraLabel()` under the hood
  (`UIActor.java`'s own "Label" case) - a `TextraLabel`, never the raw `Label` class. This line had
  never actually executed in a real playtest before now (every prior round's own status line said
  "not yet playtested"). Fixed by retyping the field as `TextraLabel`.
- **Reroll pricing swapped to the right button**: the escalating weekly surcharge from #63 had
  landed on "Re-roll Shop Type" - user correction: it belongs on the button that re-rolls a shop's
  CARD CONTENTS instead. "Re-roll Shop Type" reverted to a flat cost, no surcharge. The Armory's own
  "Re-roll Inventory" reverted entirely back to its original hard once-per-7-days cooldown (the
  #63 redesign for Armory specifically is undone). Not yet re-targeted onto the generic restock
  button at this point in the round - see #65.
- **"World" HUD button moved and renamed to "Info"**: was visually overlapping the minimap's own
  corner (anchored `menuActor.getX() - width - 4` at its old ~45px width, which undercuts the
  minimap's own right edge). Renamed, narrowed, and re-anchored off the minimap's own right edge
  instead of the menu button's left edge, so it can't overlap either neighbor regardless of
  `hud.json` values changing later.
- **Day/Week tracker**: `TimeOfDayActor`'s HUD panel gained a third row. "Day" now shows DAY-OF-WEEK
  (cycles 1-7) instead of the raw ever-incrementing absolute day count, with a separate "Week"
  counter alongside it - `Day 1, Week 0` through `Day 7, Week 0`, then `Day 1, Week 1`, per user
  spec. Purely a display re-derivation - `World.getCurrentDay()`'s own absolute semantics (what
  every other week-boundary calculation in the mod, e.g. Guard pay and the reroll surcharge, keys
  off) are untouched.
- **Starting Wood/Stone**: Easy now grants 100/100, Normal 50/50 (`DifficultyData.startingWood`/
  `startingStone`, new `config.json` fields, difficulty-scaled like every other starting resource).

Compiled clean after 2 real issues caught and fixed mid-round (an unused `Table` import left over
from an earlier round's Guard Info dialog removal, and one stale `FAST_TIME_MULTIPLIER` reference
in `WorldStage.java` a prior tuning-config conversion had missed). Deployed - both jars spliced,
resource folder mirrored, `en-US.properties`' Speed-Up label unaffected this round. **The crash fix
specifically is playtest-confirmed real** (the user's own log showed 6 occurrences of the same
exception, the last one fatal) - the rest of this round's fixes were not yet re-tested before the
next playtest round (#65) landed.

### 65. Playtest Round: Capitol Collision Fix, Arena L2 Art, Two Real Persistence Bugs, Shop-Pool Widening, Line-Chart History — `Done (playtest-confirmed 2026-08-18)`
A large round: 4 parallel investigations into fresh playtest reports, followed by fixes for all of
them plus the previously-deferred Capitol shop-coverage content task (see #26).

- **Two real, confirmed root causes for "didn't get starting resources" + "received the starting
  items twice"** (found via a dedicated investigation, not guessed):
  - Wood/Stone genuinely were granted correctly by `AdventurePlayer.create()` (confirmed by reading
    the method in full, in order) - the bug was that `onWoodChangeList.emit()`/`onStoneChangeList
    .emit()` were never called alongside the pre-existing `onGoldChangeList.emit()`/etc., so
    `ResourceDisplayActor`'s HUD label (seeded once at `GameHUD`'s own process-lifetime-singleton
    construction) never learned the real, correctly-granted value. Fixed by adding the two missing
    `emit()` calls.
  - The doubled starting "coins"/teleport rune traced to `AdventureQuestData.prologueDisplayed`/
    `epilogueDisplayed` being declared `transient` - Java serialization silently drops `transient`
    fields on every save/load round-trip, resetting them to `false` each time, which re-queued quest
    28's ("Entering The Forsaken Realms") intro dialog and its one-time "Skip tutorial" item grant
    (a teleport rune + starting Challenge Coins) on the next `showQuestDialogs()` call after any
    reload - triggered from over a dozen ordinary places (entering a town, opening inventory, etc.).
    Fixed by removing `transient` from those two fields specifically (`completed`/`failed`, also
    `transient` on the same class, were flagged as worth a separate look but NOT touched this round -
    not confirmed to have the same bug, and a much bigger blast radius if wrong).
- **Arena Level 2 art fixed** - a real, previously-undiscovered stale-cache bug, not a missing asset
  or wrong condition: the Arena's rebuilt-town icon was evaluated ONCE at map-load time and cached
  into `OnCollide`'s `rebuiltIcon` field forever, unlike the Armory's own icon (re-read fresh every
  `draw()` call in `ShopActor.java`). `OnCollide.withRebuiltIcon(TextureRegion)` changed to
  `withRebuiltIcon(Supplier<TextureRegion>)`, re-evaluated on every `draw()` - Spellsmith's own call
  site (whose icon never changes) just wraps its icon in a constant lambda, no behavior change there.
- **Capitol fountain/statue collision fixed** - user-reported "1087" turned out to be a *tile* id
  (not a Tiled object), painted twice on the `Ground2` layer flanking the Capitol's south gate. Tile
  collision in this engine is baked per-tile-id into the tileset itself (`MapStage.loadCollision()`
  reads every `TiledMapTileLayer` uniformly, no layer-name exemption for "decorative" layers) - fixed
  by repainting both cells with the same graphic's tile id from the pre-existing `main-nocollide`
  tileset (already in active use one row over, for the gate's other decorative flanking tiles)
  instead of the collidable `main` tileset, via a small Python script decoding/patching the tmx's
  compressed tile-layer data directly (verified round-trip + XML well-formedness before and after).
- **Capitol shop-type coverage (#26) finally addressed** - the Capitol's 12 ordinary card shops and
  the player-town template's 8 (down from 9 - see below) now draw from the full catalog instead of a
  narrow 28-name generic pool: ~219 additional templates (tribal/guild-flavored names previously
  exclusive to AI-owned towns, e.g. Elf/Wolf/Golgari/Dimir) appended across all 4 rarity tiers,
  tiered using the same heuristic the AI capitals' own shops already follow (bare mono-color tribal
  names -> common, off-color-tinted "TypeNColor" shops -> uncommon, 2-color guild names -> rare,
  3-5 color wedge/big-payoff names -> mythic). 3 confirmed test/joke entries (`UnionTest`,
  `goblinKingShop2`, `ubwarhammer40K`) deliberately excluded. Per explicit user requirement, the
  existing edition-progression gate (`EditionProgression.restrictShopRewardsForCurrentTown()`)
  needed no code change - confirmed already applied unconditionally to every shop-reward-generation
  path regardless of which of the ~250 templates resolved, so a shop drawing a type the player hasn't
  researched sets for correctly renders empty rather than bypassing the gate (intended per user).
  **Also given automatic weekly content refresh, matching the Armory's own cadence**: all 20 widened
  shop objects gained `noRestock=true`, routing them through the same generic, already-existing
  `PointOfInterestChanges.getWeeklyShopSeed()` mechanism the Armory/land shops use (day-multiple-of-7
  reseed) instead of the old one-time-ever roll. This also removes their ordinary paid restock button
  (a `noRestock` side effect) - `RewardScene.armoryRestockNote()`'s "Inventory will refresh weekly"
  note generalized from a name-pattern check (`isArmoryShop()||isLandShop()`) to `!canRestock()` so
  it now shows correctly for these shops too.
- **Player-town template edit acknowledged as the new baseline**: the user directly edited
  `player_town.tmx` in Tiled, removing shop object id 58 (one fewer shop slot, "to make choosing
  what to build a little more challenging"). Verified clean by investigation before treating it as
  canonical: no dangling code references to id 58 anywhere, and ruin/rubble rendering is driven
  entirely by "does a `ShopActor` exist for this object" with no separate expected-shop-count
  anywhere - deleting the object correctly means nothing spawns or renders there, no companion code
  fix needed.
- **World Standings title** changed to "The Forsaken Realms Standings" (scaled down via `[%55]`
  to fit the available width without colliding with the Reputation/Expansion buttons).
- **Line chart replaces the round-#63 snapshot bar chart** (user supplied a mockup): town count by
  week, 5 AI colors + Player (Colorless excluded per spec), rolling 10-week window. This needed real
  new persisted state that didn't exist before - `World.java` gained
  `standingsHistoryWeeks`/`standingsHistoryCounts` (one snapshot per real week boundary actually
  crossed, not backfilled if multiple weeks are skipped by fast-forwarding, trimmed to the newest 10
  entries), recorded via a new `recordStandingsHistoryIfNewWeek()` hooked into the same
  `WorldStage.onActing()` day-tick spot `EconomyBuildings`/`TerritoryControl`'s own
  `processDaysPassed()` already runs from, PLUS self-seeded on `WorldStandingsScene.refresh()` itself
  so the chart is never stuck empty for the first several minutes of a new game before the first
  real day-tick fires. Rendered as positioned `Image`/`TypingLabel` actors added directly to
  `chartArea` (a `Table` used purely as a free-positioning canvas, not for cell layout - no row/
  column flow can express arbitrary line-chart coordinates) - uniform small square point markers per
  series rather than the distinct per-series shapes in the user's own mockup reference (color alone
  already distinguishes all 6 lines; 6 unique marker shapes would need new art for comparatively
  little added legibility). `modDetailsInfo` moved up to free vertical room for the bigger chart area
  the line chart needs (spans both button columns at y=46 instead of floating below the old, smaller
  chart).
- **Mod Details page**: added the full 16-race starting-expansion table and an explicit explanation
  of how difficulty scales how many of a race's 4 sets you actually start with (Easy all 4, Normal a
  random 3, Hard a random 2, Insane just 1), cross-checked against #4b's own table rather than
  recalled from memory.
- **Exchange dialog**: the "Gold: X  Shards: Y  Wood: Z  Stone: W" resource-summary line replaced
  with real icons (per the standing "resource symbols, not text-word suffixes" convention) - Gold/
  Shards via the standard `[+Name]` inline markup (registered on the shared font), Wood/Stone via
  real `Image` actors from `resource_icons.atlas` (that inline markup technique doesn't actually
  resolve to a picture for a second atlas - see `ResourceDisplayActor`'s own class comment for the
  same gotcha already documented there).
- **Bank dialog**: "Destroy Building"/"Close" converted from two stacked full-width rows to a
  side-by-side half-width pair via the same `addHalfButton()` pairing already used for Deposit/
  Withdraw above them and for the Guards dialog's own Info/Close.

Not yet playtested - needs the user to confirm the Mod Details/Guard Info pages no longer crash, try
the corrected reroll pricing, walk into the Capitol's fountain area, upgrade an Arena to Level 2 and
watch its icon actually change, browse the widened Capitol/town card shops over a real week boundary,
and watch the new line chart accumulate real data points over several in-game days.

### 66. First Real Playtest of #65: Line-Chart Bug, Minimap Fog/Marker Desync, Shop-Widening Follow-ups — `Done (playtest-confirmed 2026-08-18)`
Seven items from the first real playtest of round #65's work, including a real bug found in that
round's own line chart before it was even confirmed by the user.

- **Day length 600s -> 400s** (`tuning.json`).
- **Line chart legend/axis-label collision, self-inflicted bug fixed**: `plotY`/`plotH` had been
  computed as if the legend's space was reserved at the BOTTOM of `chartArea`, while the legend-
  drawing loop itself was actually top-anchored (`ly = h - (row+1)*12`) - the two were never
  consistent, so the Y-axis max-value label landed almost exactly on the legend's first row (the
  user's own screenshot showed a stray "16" above the Green swatch). Layout math redone so both
  agree: legend reserved at the top, x-axis labels at the bottom, plot in between. Also moved the
  whole chart left (`x: 272 -> 225` in `world_standings.json`) per user request, clear of
  `standingsList`'s real visible content.
- **Research Lab edition names now show the 3-letter code**, matching SpellSmith's own format
  (which gets it for free from `CardEdition.toString()`'s `name + " (" + code + ")"` - Research
  Lab builds its own label text instead, so the code needed adding explicitly in two places:
  the per-edition list row and `editionDisplayName()`, used by the "Researching: X" header).
- **Manual reroll parity restored for the widened noRestock shops** (user decision, asked directly
  after the plain restock button "went missing" - it was removed as a `noRestock` side effect from
  round #65's own weekly-refresh change): the Armory's own manual "Re-roll Inventory" mechanism
  (flat cost, hard 7-day cooldown) extended to any non-Armory `noRestock` ordinary card shop too
  (land/booster shops excluded - nothing meaningful to reroll on those). Confirmation dialog text
  and the edition-restriction trigger tag both generalized from Armory-specific wording.
- **Real minimap bug found and fixed - town markers vanishing/getting "cut off" by terrain growth**,
  confirmed via investigation to be the SAME underlying mechanism already root-caused and fixed
  once before (`MOD_CHANGELOG.md`, 2026-08-09 - "fog overlay holds tile COPIES, not a live view of
  biomeImage") but only patched at ONE of its actual call sites (`World.refreshWorldMapMarkers()`).
  `redrawAllPoiMarkers()` (used by both town-capture repaints and daily territory-growth repaints)
  paints markers onto `biomeImage` only - never onto `fogOfWarPixmap`, which is what
  `World.getBiomeImage()` actually returns (and every on-screen minimap reads) whenever fog of war
  is enabled, which it is for this plane. A captured town's marker could go missing entirely (fog
  copied the marker-less tile moments before the marker was drawn), and a nearby town's marker
  could look partially erased (only the tiles inside that day's newly-claimed growth ring got
  re-synced, stranding the rest of a marker spanning multiple tiles). Fixed with a new
  `refreshFogForMarkerRect()` helper, called right after each of the two marker `drawPixmap()`
  calls inside `redrawAllPoiMarkers()` itself - covers every current and future caller in one
  place, cheap (a handful of tiles per marker, not a full-map rebuild - deliberately avoided given
  a documented perf regression earlier this session from full fog rebuilds on the daily-growth
  hot path).
- **Confirmed, not a gap: `[TFR-ShopEditions]` diagnostic logging** (built 2026-08-13) already
  covers the widened shops with zero code change needed - traced every shop-reward-generation call
  site and confirmed the edition-restriction gate applies unconditionally regardless of which
  `ShopData` name resolved, with the log line already reporting the resolved shop name, town,
  owner classification, and full restriction list for every generation (`init`/`restock`/
  `shop-reroll`/`armory-reroll`/`shop-manual-reroll`/`town-restore`/`shop-rebuild`/`shop-repair`).
- **Found in passing, not fixed, flagged for awareness**: the widened Capitol/town `commonShopList`
  pools include `DnD` (a hand-authored AFR/HBG-only crossover shop template) at all 12/8 widened
  slots. Since the edition-restriction gate unconditionally overwrites `.editions` on every shop
  (by design, confirmed no bypass), rolling `DnD` at a player-owned location silently replaces its
  intended D&D theme with the town-owner's real edition shard/unlocked list - correct restriction
  behavior, but clobbers that one template's flavor. Not fixed this round pending a decision (e.g.
  excluding hand-`editions`-authored templates from the widened pools specifically).
- **Lag report (day 23, "week 3 day 2" under the new Day/Week display) - investigated, inconclusive**.
  Checked the session's own log for volume/burst patterns; found periodic 30-50-line bursts of
  `[TFR-PrintRemap]`/`[TFR-ShopEditions]` logging throughout normal play (town visits/restores), not
  uniquely clustered near day 23 - no definitive smoking gun. Best working hypothesis, not confirmed:
  the widened shops' `noRestock` weekly reseed is lazy (only triggered on actually entering/loading
  a town, not a background per-tick cost), but if the player returned to the Capitol around a 7-day
  reseed boundary with many of its 12 shops all crossing it at once, generating full reward lists
  against the now much-larger candidate pools (up to 117 names for the common tier alone) in the
  same frame could plausibly cause a one-time stutter. Not acted on without more evidence - flagged
  for the user to help narrow down if it recurs (what they were doing at the moment it happened).

Not yet playtested.

### 67. 37-Week Playtest Round: New Lose Condition, Shop Unification, FoW Road Fix, Printing Leak, Label Collisions — `Done (playtest-confirmed 2026-08-18)`
Ten items from a 37-week playtest session, root-caused via 7 parallel investigations before any fix.
Full technical detail in MOD_CHANGELOG.md's own entry - summary:

- **New lose condition (user request)**: zero neutral towns left AND player owns nothing (no towns,
  no Capitol) = run over. Checked after every completed AI capture in `TerritoryControl.
  onMageArrived()`'s common tail (the only event that can make it true); ends the run through the
  same generalized dialog machinery as Capitol defeat (`WorldStage.triggerGameLost(message)`, the
  message-agnostic split of `triggerCapitolDefeat()`), save untouched. `[TFR-GameLost]` logged.
- **Card-shop reroll pricing, corrected to the Rotating-shop model** (user correction with
  screenshot): the widened noRestock card shops keep their tier-based restock price (2-5 shards +
  the weekly-escalating surcharge - the small "[+Refresh]" button) as a manual override ON TOP of
  the automatic weekly reseed; only the Armory family and fixed land shops stay button-less. The
  briefly-added Armory-style 150-shard button on card shops is gone. New `ShopActor.
  isWeeklyRefresh()` flag (set from the tmx's noRestock at load) drives the "Inventory will refresh
  weekly" note, since !canRestock() can no longer infer it.
- **The 4 "Rotating" shops unified into regular card shops** (user request: "should all shops not
  just be Regular shops...?"): `player_capital.tmx` objects 89-92 converted from `RotatingShop.tx`
  (a real-world-calendar daily type rotation, restock 7, excluded from type-reroll) to ordinary
  `shop.tx` objects byte-identical to the other 12 - all 16 Capitol card shops now behave the same.
  The engine's Rotating branch stays (9 other maps still use it, including the 5 AI capitals).
- **6 theme-locked shop templates pulled from the widened player pools** (`DnD`, `PowerNine`,
  `SpaceMarine`, `Necron`, `Chaos`, `Tyranid`): their hand-authored edition themes (AFR/HBG, LEA,
  40K) are always overwritten by the player-shop edition gate, so at player-owned locations they
  produced pure noise - confirmed live by the user's own "Like-New Necrons" shop selling random
  Torment commons.
- **FoW: roads inside player territory stuck at stage 2, root-caused and fixed**: daily expansion
  (`World.claimWastelandRing()`) classified tile ownership via `highestBiome()`, which the road
  pseudo-bit masks - road tiles were never claimable, so they never received the player bit and
  `isPersistentlyRevealed()` kept them hazed forever (including every neutral town's own world-gen
  3x3 road stamp - the "hazed ring around ruined towns" symptom). Roads now claim ownership BITS
  only (terrainMap/structures/minimap pixel untouched - the road stays a road everywhere else it
  matters). Old saves heal naturally as the daily re-contest sweeps run.
- **Town reveal not firing on some approaches, root-caused and fixed**: the reveal loop only
  checked the player's own chunk while collision sees the whole 3x3 loaded neighborhood - a town
  whose footprint crosses a chunk boundary could be physically entered from the neighboring chunk
  without the reveal loop ever seeing it. Reveal loop now iterates the same 3x3 neighborhood.
- **Zoomed-map label overlap, fixed globally**: labels from DIFFERENT nearby POIs (Capitol name +
  neighbor town name + event label) garbled into each other - the earlier per-POI stacking was
  architecturally incapable of helping across POIs. `MapViewScene.details()` now records every
  placed label's rectangle and shifts any would-overlap label down one row at a time until clear,
  seeded with the quest/bookmark labels already on the table.
- **Ruined/neutral town minimap icons ~15% bigger** (16 -> 18px, scaled draw of the same region,
  restored towns/every other POI unchanged, fog-sync covers the larger rect, no ghosting risk -
  restore repaints the whole ground disc first).
- **Destroy+rebuild now rolls a NEW shop type**: destroying never touched the pinned name, and the
  load-time type roll is deliberately deterministic per POI - the same type always came back.
  `destroyShopFromRewardScene()` now immediately rolls a different type from the object's own
  candidate pool via the existing `rerollShopType()` machinery (pinned, so it survives re-entry;
  sign stays hidden while rubble; fresh inventory seed). `[TFR-ShopRebuild]` logged.
- **Card printing leak, root-caused and fixed** (screenshot audit: legal TOR cards rendering with
  SOI/DMR/VMA/ZNC printings): with the all-card-variants setting on, the art-variant re-roll that
  runs AFTER the printing remap has fail-open fallbacks that ignore the edition restriction
  entirely - silently undoing the remap. Every card-pick path now re-remaps the final variant
  (`CardUtil.finishCandidate()`, the Union branch's `finishUnionCard()`, the previously-unremapped
  named-card branch, and SpellSmith's own `pullCard()`).
- **Shop duplicate cards fixed** (screenshot audit: one shop showed 8/8 slots of the same card once
  the edition restriction shrank its legal pool to one name): card slots now dedup by name
  (shuffle-then-take-front, the item path's established pattern), gracefully showing FEWER cards
  when the legal pool is small - sparse shops under a tight restriction are intended per user.
  Opt-in via a new `RewardData.uniqueCards` flag stamped only on shop-reward clones, because the
  picker is shared with deck generation (which needs 4-ofs and full counts).

**Log sweep of the 37-week session (user request): healthy.** Zero exceptions; the edition gate
held perfectly (914/914 player-shop generations restricted to exactly [TOR], across 134 distinct
widened shop types); standings history recorded weeks 0-37 sequentially with the 10-week window
capping correctly; no perf-signature bursts (the prior "day 23 lag" pattern did not recur); the
only oddity was one benign, self-recovered navigation-waypoint warning burst. The one prior-session
crash in the logs was the already-fixed InfoTextScene ClassCastException.

Not yet playtested.

### 68. Full-Mod Review Fixes: Printing-Leak Overreach, Color-Defeat Roads, Stale Prices, Broken/Dangling Data — `Done (playtest-confirmed 2026-08-18)`
A 6-dimension review workflow (32 agents: discover, 6 parallel find dimensions, adversarial
verify-every-finding) audited the entire uncommitted diff, the main questline, and the mod's full
content registries. 25 findings, 20 independently re-confirmed against live source before any fix
landed. Questline verdict: **clean** - the 5-castle chain and its flag routing are intact and
correctly wired end-to-end, no break introduced by any prior round. Fixes:

- **Card-drop dedup was leaking into enemy loot and dungeon chests (HIGH, live bug)**: the
  `uniqueCards` opt-in from #67's printing-leak fix was meant to be shop-exclusive, but
  `EditionProgression.restrictToEditions()` stamped it unconditionally - both `EnemySprite`'s
  ordinary monster-loot path and unauthored dungeon chests (`restrictDungeonRewardsForCurrentPoi()`)
  share that same helper, so both were silently deduping to unique card names instead of dropping
  normal duplicates. Fixed by adding a private 3-arg overload gated by an explicit `uniqueCards`
  boolean; only `restrictShopRewardsForCurrentTown()` (the shop path) passes `true`. This was
  already deployed and live - now corrected.
- **`Plaguelord` enemy had a broken deck reference (HIGH)**: pointed at
  `decks/standard/zombiepoisoner.dck`, which doesn't exist anywhere in the repo (only its sprite
  did). Currently unreachable (not in any biome spawn list) so it couldn't have crashed a real
  encounter, but broken content nonetheless. Repointed at `decks/standard/zombie_black_easy.dck` -
  matches both the zombie sprite and the enemy's very low difficulty/life stats.
- **Color Defeat wouldn't fully clear a defeated color's roads (MEDIUM)**: `World.
  neutralizeTerritoryOutsideRadius()` had the identical unmasked-road-bit bug #67 fixed in
  `claimWastelandRing()`, just never carried over to this sibling method - a defeated color's road
  tiles would have kept its ownership bit forever. Same fix: mask the road bit for classification
  only, the existing write logic already preserves it correctly.
- **Capitol-migrated shops could keep a stale restock price (MEDIUM)**: when a migrated shop's
  pinned name resolves outside its destination slot's own tier list, `MapStage.java`'s price-write
  loop never touched it, leaving a shared/cached `ShopData` holding a leftover or default-0 price.
  Now applied unconditionally after resolution, regardless of pin/tier-list path.
- **`Shapeshifters` → `Shapeshifter`**: a 9-occurrence typo in `forest_capital.tmx`'s rare-tier
  shop list referenced a shop name that doesn't exist (`shops.json` has the singular). Fixed.
- **14 more dangling shop-list/POI references confirmed, left for deliberate authoring, not
  guessed at**: 5 colors' worth of `Instant6{Color}` shops (a whole missing tier-6 trio leg
  alongside the already-authored Enchantment6/Creature6), 7 bespoke unauthored shops tied to
  specific hand-built locations (`GenerousShop`, `MedalShop`, `BloomburrowBoosters`,
  `EldraineBoosters`, `OutlawBoosters`, 4× `WanderingMerchant{Color}`), and 5 biome-referenced POIs
  with no defined counterpart (`GroveCentaur`, `CaveG8`, `CaveR1`, `CaveDragon`, `MageTower White`)
  - none had a plausible rename target, so none were touched; each currently just means that one
  shop slot or POI silently never appears rather than anything crashing.
- **Cosmetic-only, no behavior change**: 3 stale doc comments describing designs already reverted
  earlier this session (Color Defeat's quest-flag hook comment pointed at the wrong file; two
  `RewardScene.java` comments described the briefly-tried Armory escalating-surcharge design) all
  corrected to match the actual current code; dead `EconomyBuildings.isLandShop()` (orphaned by
  #67's restock-note rework) removed.

Also noted, not touched (harmless, no fix needed): 6 orphaned themed shop templates and an unused
`ColorlessBoosterPackShop` (both from #67's own pool cleanup), 5 legacy "plain" town POI types
superseded by the Generic/Identity/Tribal system, an unwired stock `Naktamun` capital template, a
fully-built but never-placed `Camel Cave` miniboss dungeon, and 3 enemies with harmless stray
`null` entries in their questTags arrays.

**Log review of the play session that immediately preceded this round** (new Insane/Kor game,
~4 real hours, 80 in-game weeks): zero exceptions/errors, only one harmless one-off
("Can not find card/token" during world-gen, no stack trace, never recurred). All 292
`[TFR-PrintRemap]` entries were unique cards landing in legal edition buckets - the printing-leak
fix (#67) is holding up under real play. Player built to 6 towns and held them most of the session,
then came under sustained multi-color siege in the final ~20%, ending at 2 towns with the Capitol's
guard broken twice in the log's last two lines (no capture line followed, so it likely survived to
close, but worth an in-game check). Color Defeat never fired and the new lose condition never came
close to misfiring even at 2 towns remaining.

Not yet playtested.

### 69. New Content, Color Defeat Discoverability, Difficulty/Onboarding Cross-Links — `Done (playtest-confirmed 2026-08-18)`
Drafted and authored the review-flagged content gaps from #68 (each verified against real atlas
regions, real items/editions, and confirmed-reusable map templates before writing - none invented),
plus the two discoverability/onboarding improvements from the earlier suggestions discussion:

- **13 new shops authored, wiring 13 of #68's 14 dangling shop-list references** (the 14th,
  `Shapeshifters`, was already fixed as a typo in #68): `Instant6{Black/Blue/Green/Red/White}` - a
  full 5th color-shop tier matching the existing Enchantment6/Creature6 2+6-split shape, filling
  out all 5 story capitals' commonShopList. `GenerousShop` (Three Tree City) - a broad 4-type card
  shop. `MedalShop` (Valor's Reach Arena, noRestock) - a guaranteed trophy case for all 6 of the
  Arena's own existing Medal items (modeled on the existing OmenStones one-of-each pattern).
  `BloomburrowBoosters`/`EldraineBoosters`/`OutlawBoosters` (Three Tree City, Kenrith's Court,
  Omenport) - real-edition-restricted booster shops (BLB/ELD/OTJ, each confirmed booster-capable).
  `WanderingMerchant{Black/Blue/Green/Red}` - one per matching-color Story location (Gitrog Bog,
  Wizard Palace, Squirrel Farm, Tarnation), a 2+6 mixed-type/color-filtered shop.
- **3 new dungeon/cave POIs + 1 new dungeon authored, wiring 4 of #68's 5 dangling POI
  references**: `CaveG8`/`CaveR1`/`CaveDragon` (green.json/red.json) all point at confirmed-generic,
  already-multiply-reused cave maps (`cave_mimic.tmx`, `cave_treasure.tmx`,
  `cave_multilevel_3/cave_21.tmx`); `MageTower White` (white.json) points at
  `magetower_4_monastery.tmx`, already shared by the colorless- and blue-pool instances of the
  same tower. The 5th, `GroveCentaur`, had no reusable map (the Grove family is one-map-per-entry
  and all 12 real grove maps are already claimed) - rather than leave the dangling reference or
  fabricate a new map, it was removed from green.json's pointsOfInterest list.
- **Color Defeat discoverability**: new `World.colorDefeatDay` (persisted `Map<String,Integer>`,
  same pattern as `defeatedColors`), stamped by `TerritoryControl.defeatColor()` the same moment
  `setColorDefeated()` fires. `WorldStandingsScene` now tags a defeated color's Town Count cell
  "0 (Defeated Day N)" in red instead of a bare, indistinguishable-from-never-expanded 0.
- **Difficulty/onboarding cross-links**: `NewGameScene`'s difficulty-help dialog gained a 4th
  "Territory" tab showing the exact Base Mage Cap and Towns-per-Bonus-Mage numbers for the selected
  difficulty (computed with the identical formula `TerritoryControl.maxActiveMagesPerColor()` uses,
  not a separate table, so they can't drift) - Mod Details already described these effects in prose
  but can't be opened before a game exists, so a new player choosing difficulty had no way to see
  the actual numbers. The stock intro quest's mage-in-the-cave dialogue (quest 28) gained one line
  introducing the five-color territory premise and pointing at World Standings.

Not yet playtested.

### 70. Race Selection "?" Help Button — `Done (playtest-confirmed 2026-08-18)`
User request, after liking the Difficulty tab's new Territory info (#69): the same kind of
in-context help, on the Race row. Clicking it shows the currently-selected race's 4 associated
card sets and, cross-referencing the currently-selected difficulty, how many of those 4 you'll
actually start unlocked with (randomly chosen) - both values pulled from the real
`AdventurePlayer.create()` starting-unlock logic (`Config.getConfigData().raceEditions` lookup,
the `{4,3,2,1}`-by-difficulty-index array), not restated by hand, so it can't drift from what a
new game actually does. New TFR-specific `ui/new_game.json` + `ui/new_game_portrait.json`
overrides (confirmed required: Forge's resource fallback is whole-file, never per-element merged,
so a partial diff file wouldn't work - full copies of the base files with one added button, same
approach Innistrad's own override already uses for its background swap).

Not yet playtested.

### 71. Stone HUD Fix + Side-Quest Dungeon Guarantees — `Done (playtest-confirmed 2026-08-18)`
Two items from the 2026-08-16 morning play session (new Werewolf/Insane game):

- **"My stone disappeared" (user report) - root-caused as a DISPLAY bug, resources were never
  lost**: `AdventurePlayer.load()` correctly restores wood/stone from the save but never emitted
  the `onWoodChange`/`onStoneChange` signals - `GameHUD`'s `ResourceDisplayActor` (a
  process-lifetime singleton that only updates via those signals) kept showing the PREVIOUS game
  state's values (usually 0) while the real loaded amount sat invisible underneath. Identical bug
  class to the create()-path fix from #64; the load path was missed then. Two-line fix: emit both
  signals at the end of load(), alongside the life/shards/gold emits that were already there.
- **Side-quest dungeon existence + timer guarantees (user request: "confirm the dungeon actually
  exists... if not, spawn it into existence; when given, add 30 days to that location's timer;
  main quest locations should never time out, and exist")**. A 3-agent audit first established
  the facts: main-story locations were ALREADY fully guaranteed (all 12 required POIs are
  essential-placement with retry at world-gen, and triple-excluded from dungeon rotation by
  type/name-prefix/Story-tag) - nothing needed there. But side quests had two real holes, both
  fixed:
  1. A quest could generate while every tag-matching dungeon was rotated out (hidden in
     reserve) - the target silently bound to null, the offer text showed a raw "$(poi_1)" token,
     and the stage could never complete. Now the binding logic tag-filters FIRST and, when no
     ACTIVE match exists, picks a hidden reserve dungeon and force-spawns it on the spot (new
     `DungeonRotation.onQuestTargetBound()`: setActive(true), clear cooldown/attempts, seed
     timer, minimap refresh, `[DungeonRotation]` log) - a quest always points at a real,
     enterable location.
  2. Multi-stage quests bind ALL stage targets at generation, but rotation protection only
     covered the currently-ACTIVE stage's target - a stage-2 dungeon could despawn while the
     player worked stage 1. New `AdventureQuestData.getAllPendingTargetPOIs()` (every
     non-completed stage's target) now backs the protection check.
  3. Per the user's spec, every rotatable dungeon a quest binds gets +30 days on its despawn
     timer AT QUEST-GIVEN time (the pre-existing lazy +30-when-due extension for active
     side-quest targets remains as a second layer).

Not yet playtested.

### 72. Player/AI Edition Exclusivity + "Change" Item Token Fix — `Done (playtest-confirmed 2026-08-18)`
Two follow-ups from the #71 log review, both user-directed:

- **Player race editions now exclusive from AI color shards** (user spec: "These should be
  exclusive" - the review had spotted AFR in both the black AI shard and the player's unlocked
  set). New `EditionProgression.reservePlayerEditions()`: strips the player's race's FULL
  4-edition pool (not just the difficulty-scaled unlocked subset - a Hard/Insane run's locked
  remainder is still that character's thematic set) from the 5 AI COLOR shards. The NEUTRAL
  shard is deliberately untouched (unowned land, not a rival color; thinning it would shrink
  every neutral town's shop pool). Runs at new-game creation (right after
  `AdventurePlayer.create()` - the shard seeding itself happens earlier, inside
  `World.generateNew()`, before a player exists) AND as an idempotent load-time migration (same
  pattern as the existing Capitol/vision repair calls), so existing saves - including the
  current White Dragon run - self-heal on next load. Logs `[TFR-EditionShard] reserved for
  player...` only when something was actually removed. Note: research can still unlock
  shard-assigned editions later (research draws from the full master list by design) - this fix
  covers the STARTING configuration the user flagged.
- **"Change" item fixed** (the 1x-per-launch "Can not find card/token c_a_gold_draw" log line):
  the Rare 5000-gold "Change" trinket referenced a token script that doesn't exist -
  `c_a_gold_draw` was a copy-paste of the Charm item's `c_a_clue_draw` suffix onto the gold
  token's name. The real script is `c_a_gold_sac` (standard Gold artifact token, "Sacrifice:
  add one mana of any color"), matching the item's own naming pattern siblings (Snack ->
  c_a_food_sac, Treasure -> c_a_treasure_sac). One-word data fix in TFR's items.json. The same
  typo exists in stock Forge's common/ and Innistrad items.json - left untouched (upstream
  files, not this mod's).

Not yet playtested.

### 73. Minimap Zoom-Overlap Fix + Ante Re-roll + Ante Buy Back — `Done (playtest-confirmed 2026-08-18)`
Three items from a fresh user report/request round:

- **Minimap labels STILL overlapping (user follow-up on #43/#66)**: root-caused for real this time -
  the round-7 collision-avoidance system (`MapViewScene.placeDetailLabel()`) placed labels at their
  minimum legal clearance (zero margin, edge-to-edge), which is fine at build time, but
  `zoomIn()`/`zoomOut()` reposition every label with a uniform scale+translate transform that
  shrinks the pixel GAP between two labels' anchors while each label's own on-screen SIZE never
  changes - so any pair sitting at that zero-margin clearance collapses into overlap on the very
  first zoom-out click. New `resolveLabelOverlaps()` re-runs the identical shift-down-until-clear
  algorithm against every TypingLabel's post-transform position at the end of each zoom step,
  covering all 3 label-bearing overlay modes (details/events/reputation), not just the one the
  original fix targeted. `details()` also gained the same self-cleanup its 3 sibling overlay
  builders already had (a real, if unconfirmed-as-the-live-trigger, gap: it never cleared its own
  previously-placed labels before rebuilding).
- **Ante Re-roll (50 Shards, user spec)**: shown right after the existing "these cards were chosen
  to ante" reveal, keeping the exact same selection rules (rarity-matching, etc.) - re-rollable
  repeatedly while affordable. Touches shared, non-Adventure-specific engine/GUI code
  (`forge-game`/`forge-gui`, used by every Forge client including network play), so implemented
  conservatively: `Game.rerollAnte()` (new, additive-only method reusing the exact same
  `chooseCardsForAnte()` the original roll uses, reading the match's own rules internally so a
  reroll can never apply different selection rules than the roll it replaces) and
  `IGuiGame.revealAnteCards()` (new interface method with a default that's byte-for-byte identical
  to the old direct `reveal()` call, so every non-overriding `IGuiGame` implementation - network
  play, any future client - is completely unaffected). Only `MatchController` (mobile match
  screen, already home to other Adventure-aware branches) overrides it, gated to Adventure mode
  only, and deliberately reuses `reveal()` UNCHANGED for the actual card display (zero risk to
  that shared rendering) plus this class's own existing `showConfirmDialog()` for the reroll
  Yes/No loop - no new dialog/threading code.
- **Ante Buy Back (150% of sell value, user spec, "remember the difficulty multiplier")**: added
  to the "Card Lost" ante-result popup (`DuelScene.showAnteCardPopup()`), reusing
  `AdventurePlayer.cardSellPrice()` as the base - the SAME difficulty-scaled (`sellFactor`)
  calculation the sibling "Auto-Sell" button on the "Card Gained" side already uses, rather than
  introducing a second multiplier (`EconomyBuildings.scaledCost()` is documented as deliberately
  NOT applied to card values). Only offered when currently affordable, matching Auto-Sell's own
  "only offer when it makes sense" gating style.
- **Cosmetic limitation on both new ante buttons, confirmed not assumed**: `FOptionPane`/
  `SOptionPane` (the in-duel dialog family both buttons live in) render text through plain
  `FLabel`/`FButton`, not the `TypingLabel` glyph pipeline - the same reason the existing
  "Auto-Sell (150 gold)" button already spells out "gold" instead of using the glyph. Both new
  buttons follow that same established convention (plain "50 Shards"/"225 gold" text).

Deployed: all 6 touched classes spliced into both jars across all 3 modules they span
(`forge-game`: `Game.class`; `forge-gui`: `IGuiGame.class`, `FControlGameEventHandler.class`;
`forge-gui-mobile`: `MapViewScene.class`, `DuelScene.class`, `MatchController.class`,
`EconomyBuildings.class` for the new `ANTE_REROLL_SHARD_COST` constant), spot-checked via `javap -p`
in both the mobile-dev and desktop jars. Not yet playtested.

### 74. Weekly Mine Payouts + Ante Cost Tuning + settings.json Relocation — `Done (playtest-confirmed 2026-08-18)`
Seven-item user request, all delivered together:

- **`tuning.json` moved and renamed to `config tables/settings.json`** (user spec) - sits alongside
  `items.csv`/`enemies.csv` in the same subfolder now. `TuningData` class name unchanged, only the
  backing file moved; `Config.java`'s load path updated (both the plane-local and common-fallback
  string), old `tuning.json` deleted from the source tree and correctly purged from the install by
  the resource mirror.
- **Mines: daily → weekly payout, calendar-aligned** (user spec: "Same schedule 7,14,21... if you
  build it on day 3, it will still payout day 7 the first time"). Previously a bare, unconditional
  `5 * daysPassed` add on every calendar day with zero per-building state. New
  `PointOfInterestChanges.economyBuildingLastPayoutDay` (per-town, per-type, seeded to the
  construction/migration day) plus the exact same `((lastPaid/7)+1)*7` "fixed shared payday"
  boundary-crossing loop the Guard weekly-salary pass already uses - confirmed via investigation
  as the correct calendar-aligned pattern, NOT the shop-reseed "rolling N days since last time"
  pattern (which would have made a day-3 mine pay day 10, not day 7). Old saves migrate for free -
  a missing entry reads as "never paid" (day 0), so an existing mine's first payout under the new
  system lands on the very next day-7-multiple, no explicit save migration needed.
- **New weekly amounts** (user spec): Gold 50/week, Wood 25/week, Stone 25/week, Shards 20/week -
  replacing the old flat 5/day-for-every-type constant. All 4 now independently tunable via
  `settings.json`.
- **Mine tooltip updated with icons** (user spec: "using icons"). Investigation found the atlas
  backing Wood/Stone icons only has `Lumber`/`Stone`/`GoldPile` regions (no Shards image at all)
  AND that `[+Wood]`/`[+Stone]` inline glyph markup is recognized but silently fails to render a
  picture (a known, already-documented bug - `ResourceDisplayActor`'s own class comment). So Gold/
  Shard Mine tooltips use the proven-working `[+Gold]`/`[+Shards]` markup; Lumber Mill/Stone Mine
  use real `Image` actors instead, the same workaround `refreshExchangeDialog()` already
  established for exactly this bug. Text also updated day→week throughout.
- **Ante Re-roll: escalating cost** (user spec: "+50% shards per re-roll, starting at the 50 shards
  we currently have"). `MatchController.revealAnteCards()`'s reroll loop now tracks a local
  per-reveal counter; cost = `scaledCost(base) * rate^rerollCount` - reroll 1 is the plain base,
  reroll 2 is 1.5x that, reroll 3 is 1.5x again. Resets to base fresh at the next duel's ante roll.
- **Ante costs migrated into `settings.json`** (user spec: add both Ante Re-roll and Buy Back to
  the tunable config). `EconomyBuildings.ANTE_REROLL_SHARD_COST` (hardcoded constant) removed,
  replaced by `TuningData.anteRerollBaseShardCost`/`anteRerollEscalationRate`; `DuelScene`'s
  hardcoded `* 1.5f` Buy Back multiplier replaced by `TuningData.anteBuyBackMultiplier` - same
  migration pattern `dayLengthSeconds` established when `TuningData` was first created.
- **Mod Details updated** (user spec: "update any documentation... with the new info"). New
  paragraph covering the weekly mine schedule and both Ante additions - investigation confirmed
  zero prior mentions of Mine buildings, payouts, or Ante mechanics anywhere in this page or any
  other in-game info screen, so this is genuinely new coverage, not a correction.

Deployed: all touched classes spliced into both jars (`TuningData`, `Config`, `EconomyBuildings`,
`PointOfInterestChanges`, `MatchController`, `DuelScene`, `WorldStandingsScene`), plane resources
mirrored (old `tuning.json` correctly purged from the install, new `config tables/settings.json`
correctly copied and re-validated as parseable JSON with all 19 keys present), spot-checked via
`javap -p` (all new fields/methods confirmed present, `ANTE_REROLL_SHARD_COST` confirmed gone).
Not yet playtested.

### 75. Buy Back Rarity Floor + Ante Re-roll Visibility Fix + Opt-In Inn Tournament AI Simulation — `Done (playtest-confirmed 2026-08-18)`
First live-playtest feedback on round 14's Ante work, three items delivered together:

- **Ante Buy Back price floor by rarity** (user report: "3 gold" for a real card on Insane,
  "let's have minimums... Common 50g, Uncommon 100g, Rare 300g Mythic 500g"). New
  `TuningData.anteBuyBackMinCommon/Uncommon/Rare/Mythic` (also added to `settings.json`); new
  `DuelScene.anteBuyBackMinPrice(PaperCard)` helper switches on `card.getRarity()`, falling back
  to the Common floor for anything not Mythic/Rare/Uncommon. `buyBackPrice` is now
  `Math.max(150% * cardSellPrice, anteBuyBackMinPrice(card))` - the floor only ever raises the
  price above what the existing 150%-of-sell-value formula would already charge.
- **Ante Re-roll visibility bug fixed** (user report: "I did not see the Ante Re-roll option").
  Root cause: `MatchController.revealAnteCards()` used to check `player.getShards() < cost`
  BEFORE showing the reroll confirm dialog, so a player short on Shards never saw the option at
  all - looked identical to the feature not existing. Fix: the confirm dialog (cost stated) now
  always shows; affordability is checked only after the player picks "Re-roll", with a clear
  insufficient-Shards message otherwise. Added `[TFR-AnteReroll]` diagnostic logging at every
  decision point per this project's standing logging convention.
- **Inn tournaments: opt-in real AI-vs-AI simulation** (user: "I assume currently it's just a coin
  flip... have the two AI's actually simulate their match, behind the science... By default, have
  this unchecked"). Investigation confirmed the assumption exactly - `EventScene.startRound()`'s
  AI-vs-AI branch was a bare `MyRandom.percentTrue(50)` with the mod's own `//Todo: Actually run
  match simulation here` still in place. New `SettingData.simulateInnTournamentAIMatches` global
  toggle (default off), a new Settings-screen checkbox, and `startRound()` now routes AI-vs-AI
  pairings through `DeckTesterSimulator.runBatch()` - the same real `Match`/`Game` engine the
  Arena's Deck Tester already uses - for `eventRules.gamesPerMatch` games per pairing when the
  setting is on, async via a collected-list + `AtomicInteger` pending-counter converging on a new
  `proceedAfterAiResolution()` helper. Applies to the user's current save immediately (a global
  setting, not new-game-gated). Added `[TFR-InnAISim]` diagnostic logging.

Deployed: all 6 touched classes spliced into both jars (`SettingData`, `SettingsScene`,
`EventScene`, `MatchController`, `TuningData`, `DuelScene`), plane resources mirrored (4 new
`anteBuyBackMin*` keys in `settings.json` confirmed live), `en-US.properties` copied directly
(its own separate deploy step, not jar-bundled or plane-mirrored), spot-checked via `javap -p` in
both jars. Not yet playtested.

### 76. Inn Tournament Ante Removal + AI-Sim Checkbox Relocation + World-Gen/Icon Art Overhaul + Real Launch Icon — `Done (playtest-confirmed 2026-08-18)`
First live-playtest feedback on round 15's work, plus two new user-authored art assets, six items:

- **Ante removed from Inn tournaments** (user: "Let's please remove Ante from all Inn tournaments").
  Investigation traced Adventure's ante enablement to a single line, `DuelScene.enter()`'s
  `rules.setPlayForAnte(...)`, which already branches on `eventData` earlier in the same method
  (~15 other places) but never consulted it for ante specifically. One-line fix adds
  `eventData == null &&` to that condition - a no-op for every non-event caller (dungeon fights,
  wandering enemies, Arena) since they all pass `eventData = null`. AI-vs-AI matches needed no
  change: the opt-in simulate path (`DeckTesterSimulator`) already forces ante off unconditionally,
  and the default coin-flip path never constructs a `Game`/`GameRules` at all.
- **AI-sim checkbox relocated onto the Inn Tournament screen itself** (user: didn't find round 15's
  Settings-screen checkbox - "There should be a check-box in the Inn Tournaments"). Added directly
  to `EventScene`'s existing description panel (the same screen showing entry fee/competition
  style/Start Event), still backed by the same `SettingData.simulateInnTournamentAIMatches`
  persisted preference (default off) `EventScene.startRound()` already reads - just now visible at
  the point of decision instead of buried in a separate menu.
- **AI road-building - confirmed already correct, no code change needed.** User asked to confirm
  captured towns get the same auto-road-to-nearest-holding treatment for AI colors that the player
  already gets. Investigation found `TerritoryControl.connectCapturedTownByRoad(World, POI, String
  owner)` was built owner-parameterized from its very first version (2026-08-09) and already fires
  identically from both call sites - `TownRestoration.java` (player restore) and
  `TerritoryControl.onMageArrived()` (AI capture, all 5 colors + "colorless" reverts) - via the same
  Dijkstra/`buildRoad()` machinery. Confirmed empirically diagnosable via the existing
  `[TerritoryControl] road (<owner>): ...` log line.
- **World Generation loading screen: new full-bleed background** (user-authored `Main_Image.png`,
  1024x1024, fully opaque). New `FSkinTexture.ADV_WORLDGEN_BG` (non-repeating, unlike the tiled
  `ADV_BG_TEXTURE` every other loading/UI screen still uses - deliberately NOT replaced in place,
  since that asset is shared by 10+ other screens). `TransitionScreen`'s `isloading` branch now
  detects this specific screen by comparing the loading message against `lblGeneratingWorld`
  (matching exactly the two call sites that pass it - `NewGameScene`'s initial world-gen and
  `SaveLoadScene`'s New Game Plus - and excluding "Loading World..." / the captionless mode-switch
  fade) and skips the small circular logo on top of it, since the new artwork is already a complete
  painted scene.
- **Loading icon replaced everywhere else** (user-authored `Icon.png`, 1024x1024, real alpha).
  Asset-only change - resized to 300x300 and overwrote `adv_logo.png`, plus the pixel-identical
  duplicate baked into `sprite_adventure.png`'s `ICO_ADVLOGO` atlas region (2,2,300,300) - no Java
  changes needed since every call site (main menu splash, save/load screens, Continue, exit/respawn
  transitions, etc.) already reads through those two files.
- **Real application launch icon** (user: "create a real Icon out of it and use that for the Icon to
  launch the game"). Three independent, real touchpoints identified and fixed: (1) a proper
  multi-resolution `.ico` (16/32/48/64/128/256) rebuilt from `Icon.png` for `forge-adventure.exe`'s
  launch4j build config; (2) the desktop shortcut `forge-adventure.cmd - Shortcut.lnk` - the one
  actually used to launch the game - repointed from an unrelated `Forge.ico` to the new icon; (3)
  the actual running-window/taskbar icon, which investigation found NOTHING previously set - added
  `Lwjgl3ApplicationConfiguration.setWindowIcon()` in `GameLauncher.java` (new module for this mod,
  `forge-gui-mobile-dev`), using 5 new sizes (16 through 256) generated from `Icon.png`.
- **Build tooling**: `forge-gui-mobile-dev` had never been compiled in this environment before and
  needed `build-helper-maven-plugin` (for the `GameLauncher.java` change), which wasn't cached
  locally and couldn't resolve in the usual offline (`-o`) build. Resolved once with a normal
  (online) Maven run per user's request; the plugin is now cached in the local `.m2` repo, so all
  future builds stay offline as before.

Deployed: 6 touched Java classes spliced into the correct jar(s) - `DuelScene`, `EventScene`,
`ForgeConstants` into both `forge-gui-mobile-dev` and `forge-gui-desktop`; `FSkinTexture`,
`TransitionScreen`, and the new `GameLauncher` into `forge-gui-mobile-dev` only (confirmed via jar
content listing that the desktop jar never contained the last two classes to begin with). Skin
assets (`adv_logo.png`, `sprite_adventure.png`, `adv_worldgen_bg.png`, 5 `adv_icon_*.png`) mirrored
to the live install with originals backed up (`.round16bak`) before overwriting; `.ico` rebuilt and
the desktop shortcut's `IconLocation` updated via `WScript.Shell`. Spot-checked via `javap -p`/`-v`
in both jars (including the `Simulate AI vs AI matches` checkbox string and the
`Lwjgl3ApplicationConfiguration.setWindowIcon` call in the bytecode). Not yet playtested.

**Same-day follow-up (2026-08-17, first live look at the relocated checkbox)**: user report - had
to scroll to find it, and its label rendered in the skin's default light font color, unreadable
against the panel's tan/parchment background. Fixed both: `EventScene`'s description text (built
by `AdventureEventData.getDescription()`, identical structure across all 3 event formats) is now
split on the literal `"Prizes\n"` marker that unconditionally starts its own paragraph right after
the entry-fee block in every format/status combination - the checkbox now sits between Entry Fee
and Prizes instead of at the very bottom, no scrolling needed. Its label color is forced via
`simulateAiMatches.getLabel().setColor(Color.BLACK)`, matching the `[BLACK]`-tagged `TypingLabel`
text surrounding it. Deployed: `EventScene` respliced into both jars, spot-checked via `javap -v`
(confirmed the `"Prizes\n"` split constant and checkbox string both present in the recompiled
class).

### 77. Settings-Crash Deploy Fix + Inn Checkbox Reposition + Minimap Names/Details Rewrite + New Town Reputation Building Gate — `Done (playtest-confirmed 2026-08-18)`
First live-playtest feedback on rounds 15/16 plus one new major mechanic, six items:

- **Settings-menu crash (CTD), root-caused and fixed**: user report - loading a save, opening the
  Escape menu, clicking Settings crashed to desktop every time. Live-log stack trace (`forge.log`)
  showed `NoSuchMethodError: SettingsScene$18.<init>(SettingsScene, FPref, Runnable)`. Root cause:
  a **round-15 deployment defect**, not a source bug - inserting the round-15 checkbox shifted
  javac's numbering for every anonymous inner class compiled after it in `SettingsScene.java`
  (17→18 total, then 18→19 this round), but round 15's deploy only spliced the outer
  `SettingsScene.class`, never the shifted `SettingsScene$1..18.class` family, leaving the jar's
  `$18` a stale pre-round-15 class with the wrong (2-arg) constructor. Fixed by respliced the
  FULL current `SettingsScene$*.class` family into both jars. Caught and nearly repeated the same
  mistake fixing it: the first splice attempt `cd`'d one directory too deep and passed bare
  filenames, which added the correct classes at the jar's ROOT instead of their real package path
  - caught by directly `javap`-ing the actual jar entry afterward rather than trusting the splice
  command's exit code, then corrected by re-running from `target/classes` with full
  `forge/adventure/scene/SettingsScene*.class` paths. Established going forward: always splice
  via a `ClassName*.class` glob from the classes root, never enumerate `$1,$2...` by hand or `cd`
  into a subpackage first - either mistake silently leaves stale bytecode live.
- **Inn AI-sim checkbox moved again**: user report - still had to scroll even after the previous
  fix (which split the description before "Prizes"; the Event Type/Block/Boosters/Competition
  Style/Entry Fee text alone already exceeded the panel's visible height). Re-split before the
  literal `"Pay 1 Entry Fee"` string instead - explicit user direction this time ("move it up,
  above 'Pay 1 Entry Fee'"). Falls back to whole-description-then-checkbox when that string isn't
  present (event already joined - that text is short enough alone).
- **World-gen/loading icon, 4x bigger**: user request, `TransitionScreen`'s non-worldgen loading
  icon draw scale multiplier `1f` → `4f`.
- **Minimap Names/Details rewritten** (4th time this exact class of bug was reported). Investigation
  found two independent root causes: (1) `names()` was a genuine stub - cleared labels, added none
  - while `details()` carried town-name AND event/set-info labels fused together; town-name logic
  moved from `details()` into `names()`, matching the button text (`map.json`'s "Names"/"Details").
  (2) The real mechanical cause of garbled/overlapping text: `placeDetailLabel()`'s collision
  rectangles were built from `TypingLabel.getWidth()/getHeight()` immediately after `addActor()`,
  but textratypist 0.8.2's `TypingLabel`/`TextraLabel` never call `setSize()`/`pack()` internally -
  every rectangle was 0x0, so `Rectangle.overlaps()` could never return true and the shift-down
  loop never fired, regardless of how many rounds re-timed *when* it ran. Fixed once, centrally, in
  `placeDetailLabel()`: `label.pack()` right after `addActor()`, before any width/height read. Also
  routed `events()` ("Reputation" button) and `reputation()` ("Landmarks" button) through
  `placeDetailLabel()` too - they had zero collision avoidance before this, now share the same fix.
- **Town Reputation - new mechanic, reusing an existing base-game field.** User request: expand the
  already-present-but-inert per-town "reputation" stat (`PointOfInterestChanges.reputation`, id-0
  slot, previously only nudging shop prices) into a real gate on how fast a player's own town can
  rebuild - distinct from the mod's own AI-color `ColorReputation` diplomacy system. Spec, delivered
  exactly: +1 reputation on restoring a town (`TownRestoration.buildRestoreTownDialog()`, via the
  existing `DialogData.ActionData.addMapReputation` field - no new imperative code needed there);
  +1 on upgrading to a Capitol (`upgradeToCapitol()`, migrated forward across the id-change the same
  way guards/buildings already are, then +1 on top - without this the town's own upgrade would have
  silently wiped its reputation); +1 on defeating an attacking mage (`DuelScene.afterGameEnd()`,
  same `winner && !isArena && eventData==null` guard `ColorReputation` already uses, granted to
  `enemy.territoryTarget`'s town, gated to player-owned/restored targets only). Every reputation
  point unlocks `BUILDINGS_PER_REPUTATION=3` more build slots (a town's 9 slots need 3 rep, a
  Capitol's 25 need 9) via new `TownRestoration.countBuiltBuildings()` (counts existing
  `shopRebuilt_*` map-flags - already uniformly set by every building type: shops, Inn, Arena,
  Spellsmith, economy buildings) and `maxBuildableBuildings()`/`hasReputationForAnotherBuilding()`.
  Gate wired into both real build-request entry points (`ShopActor.onPlayerCollide()`,
  `OnCollide.onPlayerCollide()`), showing a new `buildReputationLockedDialog()` (progress-aware
  message, "Help it prosper - completing its quests raises your standing here") instead of the
  normal build dialog when blocked. Reputation loss never demolishes existing buildings (the gate
  only runs at build time); destroying a building frees its slot immediately regardless of
  reputation (`destroyBuilding()` already removes its `shopRebuilt_` flag - no new code needed,
  falls out of the existing mechanism for free); losing a town to an AI color resets reputation to
  0 for free too (capture already re-keys the POI's id via `transformInto()`, same implicit-wipe
  mechanism guards/buildings already rely on - confirmed via investigation, not touched this
  round). New Mod Details paragraph added (`WorldStandingsScene.java`).

Deployed: `MapViewScene`, `WorldStandingsScene`, `TownRestoration`, `ShopActor`, `OnCollide`,
`DuelScene` spliced into both jars (`SettingsScene`'s full corrected family too, see crash fix
above); `TransitionScreen`/`EventScene` respliced for the icon-size and checkbox-position tweaks.
Spot-checked via `javap -p`/`-v` in both jars for every new method, the corrected `SettingsScene$18`
3-arg constructor, and the new Mod Details paragraph text. Not yet playtested.

### 78. Ownership-Based Shop Price Baseline + Resource-Spawn Cap 20→30 — `Done (playtest-confirmed 2026-08-18)`
Two balance tweaks plus a log-health check, confirmed clean via investigation before implementing:

- **Shop prices now start from an ownership baseline**: cards cost 25% more at AI-owned town
  shops and 25% less at player-owned town shops (a restored town OR the Capitol), applied as a
  new fourth multiplicative factor in `ShopActor.getPriceModifier()` - composes with, doesn't
  replace, the existing per-shop/per-town reputation modifier and the `ColorReputation` diplomacy
  modifier. Neutral towns (Spawn) get neither. New `TuningData.aiShopPriceMultiplier` (1.25)/
  `playerShopPriceMultiplier` (0.75), both re-tunable via `settings.json`. Deliberately reuses
  `TownRestoration.isCurrentTownPlayerOwned()` rather than the narrower `isTownRestored()` the
  existing `colorReputationModifier()` uses - the latter gets away with skipping the Capitol case
  only because `colorOfTown()` already returns null for it too, which would have silently left the
  Capitol's own shops out of the new player discount.
- **Overworld resource-pickup cap 20 → 30**: `ResourceSpawns`' flat `MAX_SPAWNS` constant moved
  into `TuningData.maxResourceSpawns` (re-tunable, matching this file's established convention) -
  confirmed via investigation it's a single self-contained hard cap with no array/pool sizing
  coupled to it anywhere in the codebase.
- **Log health check**: reviewed the live session logs from right after round 17's deploy (a
  ~45-minute session covering town restoration, a Capitol upgrade, combat, an Inn tournament, and
  Armory purchases) - zero exceptions, zero ERROR/WARN lines. One unrelated, non-fatal Scryfall
  404 fetching a single token's art. Confirmed round 17 (crash fix, minimap rewrite, Town
  Reputation) held up under real play.

Deployed: `ShopActor`, `ResourceSpawns`, `TuningData` spliced into both jars; `settings.json`
mirrored with the 3 new keys. Spot-checked via `javap -p` in both jars. Not yet playtested.

### 79. Wood Added to Intro Template + Dungeon-Clear Despawn + Confirmations — `Done (playtest-confirmed 2026-08-22)`
User explicitly asked for repo-only work at the time ("I'm currently playing a live game, so don't
make any changes to the live files") - everything below was compiled to verify correctness but
deliberately NOT spliced into the installed jars or robocopied to `E:\GAMES\FORGE` in that same
round. **Deployed later the same day** (round 21, alongside #80 and the new #81) once the user
gave the go-ahead ("proceed to update the game, include all the changes we made today").

- **Confirmed: Fort=Wood/Cave=Stone loot (with the -25% Shard trade) was already implemented**,
  contrary to the user's own uncertainty. `RewardData.shardsSubstituteType()` (2026-08-11,
  `resourceLootVarietyEnabled`, on for this plane) rolls a 25% chance per Shard pickup to
  substitute Stone (if the current dungeon's map path contains `/cave/`) or Wood (`/fort/`)
  instead. Confirmed live and unmodified since. One known, already-documented limitation worth
  repeating to the user: the pickup still LOOKS like a shard crystal even when it grants Stone/
  Wood - the substitution only shows up in the reward popup text, not the sprite (a real sprite
  swap was explicitly deferred at the time, since it would touch ~54 shared `common/` cave/fort
  `.tmx` files).
- **Wood added to the Intro/spawn map, mirroring Stone exactly** (`maps/map/main_story/spawn.tmx`)
  - a testing template per user request. New `maps/obj/wood.tx` object template (copy of
  `stone.tx` with `type=stone`→`wood`, name/sprite renamed); new object instance in `spawn.tmx`
  (`id="86"`, `template="../../obj/wood.tx"`, positioned one tile right of the existing Stone
  pickup; `nextobjectid` bumped 86→87 to match). New `maps/tileset/wood_pickup.atlas` - rather
  than fabricating placeholder art, this reuses the plane's own already-shipped `resource_icons.
  png` Lumber icon pixels directly (a second small atlas file pointing at that same PNG, exposing
  its Lumber sub-region under the `Idle` region name `RewardSprite`'s animation system requires -
  no new image file needed, no guessed colors).
- **Common-vs-plane dungeon maps, investigated and advised (not yet acted on)**: confirmed all 20
  Fort and 30 Cave `.tmx` templates this plane spawns are 100% shared via `common/maps/map/`
  (the plane's own `fort/`/`cave/` folders hold 4 unrelated custom files each, zero overlap).
  Path resolution traced exactly: `TileMapScene`/`MapStage` always call `Config.
  getCommonFilePath()` for dungeon maps (unconditionally prepends `common/`), so a POI's own
  `"map"` string using `../The Forsaken Realms/...` is the ONLY thing that already redirects a
  few custom dungeons out of `common/` today - no separate plane-fallback mechanism like
  `getFile()` exists for maps specifically. Recommendation given: copy-and-repoint, but scoped
  ONLY to the specific templates about to be hand-edited (not a blanket copy of all 202 `common/`
  `.tmx` files this plane references) - edit just that POI's `"map"` line in `points_of_interest.
  json`, no Java changes needed either way (this entry's own advice, superseded by #80's fuller
de-duplication below). Confirmed a real, non-hypothetical reason to avoid
  editing `common/` in place: this repo actively merges from `upstream/master` (last synced ~2
  weeks ago) and upstream has a real history of revising these exact fort/cave files - an in-place
  edit risks silent clobber on the next merge, a copy in the plane's own folder doesn't. Flagged
  one non-obvious gotcha for the user's own Tiled workflow: a copied file's tileset `source=`
  paths need fixing (2 extra `../` levels - `common/`'s forts are one folder shallower than the
  plane's own) after copying, or Tiled needs to re-link them on first open from the new location.
- **Dungeon-clear despawn - confirmed missing, now implemented.** Investigation confirmed a
  dungeon that's fully cleared (every enemy killed) and exited normally previously got ZERO
  special treatment - it just sat on the map, unchanged, until its ordinary rotation timer
  happened to come due (same as if the player had never entered). Only losing a fight inside a
  rotatable dungeon triggered any despawn logic before this round. New `DungeonRotation.
  onDungeonClear(PointOfInterest)`, called from `AdventureQuestController.updateQuestsWin()`
  right where it already computes `allEnemiesCleared` (nested inside the `enemies != null`
  branch specifically - NOT a bare `allEnemiesCleared` check - since the single-enemy overworld-
  duel overload passes `enemies=null` and would otherwise trip the same flag's true-by-default
  value for every ordinary overworld win). Despawns immediately (no attempts/grace system - a
  clear is unconditional success, unlike a loss), exempts current STORY-quest targets exactly
  like `onDungeonDefeat()` already does ("story targets never vanish"), and pulls a fresh
  reserve-pool dungeon into play elsewhere afterward, same as any other despawn.
- **Dungeon rotation timing, restated in full** (`DungeonRotation.java`, all `private static
  final int` fields, opt-in via `dungeonRotationEnabled` - on for this plane):

  | Constant | Value | Meaning |
  |---|---|---|
  | `POOL_MULTIPLIER` | 5 | World-gen places 5x the normal count of every rotatable dungeon/cave; only 1/5 start visible - a despawn activates a reserve slot elsewhere, dungeons genuinely relocate. |
  | `DESPAWN_MIN_DAYS` / `DESPAWN_MAX_DAYS` | 20 / 60 | A visible dungeon's random lifetime range before it becomes eligible to despawn, re-rolled on every (re)spawn. |
  | `RESPAWN_MIN_DAYS` / `RESPAWN_MAX_DAYS` | 10 / 30 | Cooldown range before a just-despawned location can be redrawn as a fresh reserve pick. |
  | `SIDEQUEST_EXTENSION_DAYS` | 30 | Added to a dungeon's despawn day whenever a live side quest targets it (on binding, and again every time its timer comes due while still targeted) - re-applied indefinitely for a long-running quest. |
  | `MAX_QUEST_ATTEMPTS` | 3 | Failed-attempt threshold for a side-quest-linked dungeon - losses 1-2 show an "N attempts remaining" warning and leave it in place; the 3rd despawns it immediately. |

  Story-quest targets never despawn from anything (timer just re-rolls in place, and now: clearing
  it doesn't despawn it either); untargeted rotatable dungeons get no attempts grace at all - a
  single loss despawns them immediately, same as it always has.

Compiled (`mvn -pl forge-gui-mobile -am compile`) clean on the first pass for `AdventureQuestController`/
`DungeonRotation`; the map/template/atlas files are pure data, validated well-formed XML directly.
**Deployed 2026-08-18** (round 21) - jar spliced, res folder mirrored, spot-checked live (see #81's
round summary below for the mechanics and verification).

### 80. Full Dungeon-Map De-Duplication From `common/` — `Done (playtest-confirmed 2026-08-22)`
Follow-up to #79's scoped recommendation - the user opted for the full de-duplication instead
("all 202... it's only 4MB total and this way we ensure no Common override"). Executed via a
one-off Python migration script (discovery → copy → rewrite → validate), not manual edits - the
scale made that the only reliable way to do this correctly.

- **Discovery found more than 202.** The 202 figure was only the directly-POI-referenced dungeon
  templates. Scanning each one's Tiled `teleport` properties (the same mechanism `MapStage.
  loadMapFile()`/`resetMapRecursive()` already use for portal-linked sub-maps) turned up **287
  total files** in the real dependency closure - multi-room dungeons (`cave_multilevel*`, 3 sets),
  the 5 AI-color castles' interior floors, Temple of Liliana's 4 sub-areas, and a miniboss
  entrance/interior pair all pull in extra sibling `.tmx` files beyond their one POI-listed entry
  point. Copying only the 202 would have left 85 files - and every dungeon that uses them -
  still depending on `common/`.
- **Found and fixed 3 pre-existing broken POI references as a side effect**: `points_of_interest.
  json` had `"../common/maps/map/naktamun.tmx"`, `"...oasis.tmx"`, and `"...vampirecastle_4.tmx"`
  - all three missing their actual subfolder (`naktamun/`, `naktamun/`, `vampirecastle/`
  respectively; confirmed via direct filesystem search, not guessed). These were already dangling
  before this round - unrelated to the migration itself, just newly visible because the migration
  script's discovery pass tried to resolve every reference and 3 came back missing. Corrected to
  their real paths as part of this same pass rather than leaving them broken under a new home.
- **Every cross-file reference type rewritten correctly, not just copied.** Two kinds exist in
  these files (confirmed via `MapStage.java` - `<tileset source="...">` and `<property
  name="teleport" value="...">`; no `template=`/embedded `<image>` refs found anywhere in the
  scanned set). Both computed via real relative-path math (`os.path.relpath()` against each file's
  actual old/new location) rather than a hand-derived depth formula, so it's correct regardless of
  nesting depth: tileset refs still point back at the shared `common/maps/tileset/` (tilesets
  themselves were NOT copied, staying genuinely shared - only the map layouts moved), while
  teleport refs that pointed at another `common/`-hosted dungeon now point at that dungeon's new
  plane-local copy instead, using the same `"../The Forsaken Realms/maps/map/..."` convention the
  plane's own pre-existing custom dungeons already established.
- **`points_of_interest.json` updated**: all 259 `"map"` string occurrences (202 unique targets,
  some referenced by multiple POI instances) repointed from `common/` to the plane's own folder.
- **`common/` left completely untouched** - confirmed via `git status` showing zero changes
  anywhere under `forge-gui/res/adventure/common/`. This was a pure additive copy, not a move.

Validation (all before touching anything live): every one of the 287 copied files (plus the
plane's ~37 pre-existing custom dungeons, 324 total) parses as well-formed XML; every rewritten
`tileset`/`teleport` reference resolves to a real file on disk (verified by actually opening each
resolved target path, not just checking the string looks plausible); zero remaining `.tmx`
references anywhere under the plane's `maps/map/` tree still point at `common/maps/map/`
(one legitimate false-positive checked and cleared - a shared `autotiles.tsx` tileset that happens
to physically live in `common/maps/map/` rather than `common/maps/tileset/`, correctly left
pointing at `common/` since it's a tileset, not a map layout); `points_of_interest.json` re-parses
as valid JSON with zero remaining `common/maps/map/` map references. No Java code touched, so no
compile was needed for this entry - purely data/asset changes. **Deployed 2026-08-18** (round 21)
- res folder mirrored to `E:\GAMES\FORGE`, spot-checked live (see #81's round summary).

**Same-day correction: a real gap in the migration above, caught by the user opening a copied file
in Tiled and seeing "Some files could not be found."** The original validation only checked for
`<tileset source="...">` and `teleport` properties - it missed `template="..."` object-template
references entirely (used pervasively: `enemy.tx`, `gold.tx`, `manashards.tx`, `entry_up.tx`, and
~15 other generic per-object templates, present in 284 of the 287 copied files, ~7000+ individual
references). Root cause of the miss: an earlier check for this exact pattern silently reported
zero matches because it ran as a separate Bash invocation after a `cd` issued in a prior, different
invocation - this environment does not carry a `cd` across separate tool calls, so the check
actually ran from an unrelated directory and every file lookup silently failed closed. Same root
class of mistake as the round-17 `SettingsScene` jar-splice path bug: an unverified assumption
about shell/tool state persistence. Fixed by rewriting every `template=` reference the same way as
`tileset source=` (real `os.path.relpath()` recompute against each file's original common/
location, not a guessed formula). A second, independent gap surfaced during the fix: 3 files
(`naktamun/naktamun.tmx`, `naktamun/oasis.tmx`, and the `naktamun/gym.tmx` it teleports to) were
missed by the template fix too, because that fix script re-used the FIRST discovery run's file
list, which pre-dated the 3-path correction from earlier in this same round - those exact files
were still indexed under their old, wrong bare-filename keys, so the fix script's own file-lookup
silently skipped them (again: stale cached state trusted instead of re-verified). A third,
previously-unknown reference shape was found in the same pass: 3 files (`fort_white_2_humans.tmx`,
`fort_blue_1_pirate.tmx`, `grove_12_faeriedragon.tmx`) use an *embedded* Tiled tileset (`<image
source="...">` nested inside a sourceless `<tileset>` element) rather than an external `.tsx`
reference - a shape the original `<tileset ... source="...">`-anchored regex could not match at
all. Final fix pass abandoned all three prior discovery lists and instead walked the live
`maps/map/` tree directly, treating any file with a same-relpath `common/` counterpart as
migrated, and matched a bare `source="..."` attribute (covering both external-tileset and
embedded-image cases in one pattern) plus `template="..."`, applied idempotently to all 293 such
files. Re-validated after: 0 broken `source=`/`template=` resolutions, 0 broken `teleport` targets,
0 dangling `common/maps/map/` references, 324/324 files still well-formed XML,
`points_of_interest.json` still valid. **Lesson applied going forward**: for any bulk repo
migration like this, re-derive the file scope from the live filesystem at each fix stage rather
than trusting an earlier stage's cached list, and never assume a shell `cd` persists across
separate tool invocations - verify directly (e.g. `pwd`, or just use absolute paths throughout)
rather than assuming.

### 81. Capitol Upgrade Reputation Bonus Raised to +2 — `Done (playtest-confirmed 2026-09-01)`
User spec (2026-08-18): "When you build your capitol, let's give +2 reputation to the town vs. the
current +1." The +1 itself was a 2026-08-17 addition (see #13's history) - `TownRestoration.
upgradeToCapitol()` carries the pre-upgrade town's accumulated reputation total across the
Capitol-transform id-remap, then adds a flat bonus on top; that flat bonus is now +2
(`newChanges.addMapReputation(oldChanges.getMapReputation() + 2)`, was `+ 1`). One-line change,
same call site, no other logic touched.

**Round 21 deploy summary** (this entry, plus #79 and #80, all landed live together):
- Compiled clean (`mvn -pl forge-gui-mobile -am compile -DskipTests -o -q`), touching
  `TownRestoration.java` (this entry), `DungeonRotation.java`, and `AdventureQuestController.java`
  (#79's dungeon-clear despawn hook, compiled earlier the same day but held back from deploy).
- Verified no live game process was running first, then spliced all four affected modules'
  freshly-compiled `target/classes` (`forge-core`, `forge-game`, `forge-gui`, `forge-gui-mobile`)
  into the installed `forge-gui-mobile-dev-...-jar-with-dependencies.jar` via `jar uf` (one call
  per module, matching the module boundaries the shaded jar itself merges). Byte-for-byte verified
  afterward: extracted `TownRestoration.class`/`DungeonRotation.class`/
  `AdventureQuestController.class` back out of the live jar and `cmp`'d each against the
  just-compiled copy in `target/classes` - all three identical.
- Mirrored the plane's res folder (`forge-gui/res/adventure/The Forsaken Realms/` ->
  `E:\GAMES\FORGE\res\adventure\The Forsaken Realms\`) via `robocopy /E /XO` (additive only - no
  `/MIR`, so nothing already in the live folder could be deleted by this step). 300 of 1436 files
  copied (the rest already matched from a prior round's deploy), covering #79's `wood.tx`/
  `wood_pickup.atlas`/`spawn.tmx` and #80's 287 migrated dungeon `.tmx` files plus the repointed
  `points_of_interest.json`.
- Spot-checked the live install directly afterward: `wood.tx`/`wood_pickup.atlas` present,
  `spawn.tmx` contains the new Wood object reference, `points_of_interest.json` has zero remaining
  `../common/maps/map/` references, and the plane's live `maps/map/` tree now has all 324 `.tmx`
  files (202 migrated + 85 transitive + ~37 pre-existing custom), matching the repo-side counts
  from #80's own validation exactly.
- **Batch playtest confirmation, same round**: user confirmed a large batch of prior rounds' work
  as playtested and working - see items #3, #4, #13, #17, #19, #29, #32, #37, #41, #46, #51, #55,
  #59-#63, and #65-#78, all flipped to `Done (playtest-confirmed 2026-08-18)` above. **#27
  (Simulate Level 2 Arena Battles) was NOT included** despite being named in the same request - it
  remains genuinely `Not Started` (real unresolved design questions, no code written), flagged
  back to the user rather than silently marked done, since marking unbuilt work "done" would be a
  real documentation error rather than a playtest confirmation.

### 82. Realm of Legends Content Update, Ported — `Done (playtest-confirmed 2026-08-22)`
User spotted a Reddit post about a Realm of Legends (a stock Forge plane) content update and asked
for a review of what's new, what's not yet in this mod, and to port whatever would work. Full
diff investigated first (via a background research agent, cross-checked directly against both the
old install and the freshly-downloaded `E:\GAMES\Forge_2` 2.0.15 snapshot) before touching
anything - see the round's chat log for the itemized findings report.

**Scope, agreed with the user up front:**
- **In**: all 6 full dungeons (Ashling's Domain, Eclipsed Elven Court, Planeswalker Dueling Club,
  Idyllic Beachfront, Peaceful Clearing, An-Havva Inn), the Ancient Opal Cavern mox-opal duel
  (Nephilim Epochal), the Isolated Hut mini-dungeon (Istvan) - 8 locations, ~39 bosses, 13 items,
  38 boss decks, 2 new commander precons (Miku, Hobbit "There And Back Again").
- **Out**: the Otherworldly Market (colorless shop-hub town) and its 14 Universes-Beyond-themed
  shops - user's call: unclear interaction with #4 (Progressive Set Unlocks) since those shops
  pull from real sets outside the plane's normal edition-progression pool; needs its own design
  pass before it's safe to add. The "Otherworldly Return Card" quest item (teleports to the
  Market) was excluded along with it - dead without a destination.
- Morcant was kept in (not dropped despite having 2 real bugs in his deck - see below), per direct
  user confirmation after a genuinely ambiguous answer was clarified.

**Two real upstream bugs found and fixed rather than ported as-is:**
- `morcant.dck`: a duplicate `Champions of the Perfect|ecl` line (removed), and
  `1 Tyvar the Bellicose mat` missing its `|` delimiter before the edition code (fixed to
  `Tyvar the Bellicose|mat`) - this is almost certainly the actual cause of the Reddit-reported
  "High Perfect Morcant fight is currently bugged," and it's still present in the "afternoon fix"
  snapshot the user downloaded, not just the original release.
- `nephilim_epochal.dck`: commander line was missing its `1 ` count prefix (every other deck in
  the set has it) - added for consistency, not confirmed as a functional bug but deviated from
  the established convention.

**A name collision caught before it could silently corrupt data**: this plane already has its own
pre-existing "Autumn Willow" enemy (the Mox Emerald guardian, unrelated to anything in this
update) using the exact same `enemies.json` name and the exact same `decks/legends/
autumn_willow.dck` filename as Realm of Legends' new An-Havva Inn boss. The new one was
disambiguated as `"Autumn Willow (An-Havva)"` with its deck copied to `autumn_willow_anhavva.dck`
instead - the pre-existing fight is completely untouched. `Plagon` (An-Havva's... no, Idyllic
Beachfront's - a flying WU legend) was the opposite case: already present in this plane with its
own, more developed reward list, and reused as-is rather than overwritten; only its new
`Starfish On Your Foot` item drop was added on top, additively.

**A second real gap found only by exhaustively checking every single card reference, not
spot-checking:** after porting the 38 new decks + 2 precons, a full pass resolving all ~3,000
individual card lines against this repo's own `cardsfolder` turned up 64 apparently-missing
cards - at first glance looking like this repo just hadn't synced recent upstream card additions.
Cross-checking the SAME names against the just-downloaded snapshot's own bundled
`cardsfolder.zip` (rather than this repo's copy) resolved all but one: 60 real, valid custom card
files (mostly the "MBC"/"HOB"/"HOC" custom-set cards this exact update needs - the named boss
commanders themselves, plus most of the new Hobbit precon's card pool) existed in the *new
snapshot* but hadn't propagated into this repo yet, so they were extracted from the snapshot's zip
and added to `forge-gui/res/cardsfolder/` directly (61 new `.txt` files total, including
`nephilim_epochal.txt` found earlier). One more (`Gláin the Mighty` in the Hobbit precon) turned
out to be a plain spelling error in the source deck for the real card `Glóin the Mighty` -
corrected. The one genuine, un-fixable gap: `Mathise, Surge Channeler|slx` doesn't exist anywhere,
including the new snapshot itself - dropped from `grandmother_goby.dck` (one line out of ~90;
everything else in that deck is intact). **Without this check, 10 of the 39 new bosses across 5
of the 8 dungeons would have shipped with commander decks that fail to load entirely** (their own
namesake commander card wouldn't have resolved) - this wasn't caught by the background research
agent's spot-check, only by mechanically resolving every single card line afterward.

**Mechanics, mirroring the #79/#80 dungeon-migration precedent exactly:**
- The 8 `.tmx` files were copied into this plane's own `maps/map/<biome-folder>/` tree (same
  folder-per-color convention this plane already uses: `barbariancamp`=red, `evilgrove`=black,
  `grove`=green, `merfolkpool`=blue, `fort`=white, `cave`=colorless). Their `tileset`/`template`
  references already used the identical `../../../../common/maps/...` depth this plane's own
  files use (both planes sit at the same folder depth under `res/adventure/`), so almost nothing
  needed rewriting - the one exception was a single plane-local `../../obj/treasure.tx` template
  (used by 5 of the 8 files) redirected to the shared `common/maps/obj/treasure.tx` instead (a
  near-identical template, confirmed by diff - only a minor reward-tier difference).
- POI entries added to `points_of_interest.json` (this plane's own schema, confirmed identical to
  Realm of Legends': flat name list per color in `world/biomes/<color>.json`'s own
  `"pointsOfInterest"` array, which is the actual world-gen wiring mechanism - a plane's `type`
  field alone doesn't place anything). **Ancient Opal Cavern was retyped `castle`→`dungeon`**:
  in stock Realm of Legends `type:"castle"` is inert, but in this mod it's load-bearing for
  Territory Control (`World.isEssentialPoi()` - castles/capitals are Territory Control anchors
  requiring `TerritoryControl.ensureCapital()` wiring). A single-boss story dungeon has no
  business being treated as a faction capital; `"dungeon"` + the existing `questTags:["Story"]`
  tag (which `DungeonRotation.isRotatableData()` already exempts from the rotation pool
  entirely) gives it the same static, never-despawning behavior with none of the collision risk.
- Enemy sprites (`sprites/enemy/...`) and boss deck paths (`decks/legends/*.dck`) needed zero
  path rewriting either - both resolve identically regardless of which plane references them
  (sprites via the shared `common/` tree, decks via a plane-relative path that's the same shape
  in both planes) - confirmed directly rather than assumed, since this plane's own #79/#80 rounds
  already established that assumption-driven porting is exactly how bugs slip through here.
- Item icon art was NOT available for free the way tilesets/sprites were - `items.atlas`/
  `items.png` are genuinely plane-local, hand-maintained files (this plane has been adding its own
  custom items to them all session, e.g. the Torch line). The 13 new 16x16 icon regions were
  cropped directly out of Realm of Legends' own `items.png` (same 480px-wide sheet convention)
  and pasted into a newly-appended row at the bottom of this plane's own `items.png`
  (1056px→1072px tall), with matching new region entries appended to `items.atlas` - real pixel
  art carried over, not placeholder squares.

**Validation, all before calling this done:** all 8 `.tmx` files re-verified well-formed XML with
every `source=`/`template=` attribute (walked generically via ElementTree, not a hand-picked
attribute list) resolving to a real file on disk; all touched/new JSON files (`points_of_interest.
json`, all 6 `world/biomes/*.json`, `enemies.json`, `items.json`) re-parsed as valid JSON with the
expected new entries present and zero accidental name duplicates; all 13 new icon regions spot-
checked to contain real non-transparent pixel data, not blank crops; and - the big one - every
one of the ~3,000 individual card lines across all 40 deck files (38 legends + 2 precons)
confirmed to resolve to a real `cardsfolder` entry, with the process documented above for how the
64 initial misses were run down to zero.

No Java code touched - purely data/asset changes (map, JSON, deck, card-definition, and sprite-
atlas files only), so no compile was needed or run for this round. **Deployed 2026-08-18**: no jar
splice needed (no Java changed) - the plane's res folder was mirrored to `E:\GAMES\FORGE` via
`robocopy /E /XO` (59 of 1484 files copied, the rest already matched), plus the 61 new card
`.txt` files copied directly into the deployed `res/cardsfolder/` tree alongside the existing
`cardsfolder.zip` - confirmed via `CardStorageReader`'s own source that it loads loose `.txt`
files under `cardsfolder/` AND the zip archive additively into the same result set, so no zip
rebuild was needed. Spot-checked live afterward: new `.tmx` files present, new card `.txt` files
present, `points_of_interest.json` has the new entries, `items.png` is the new 1072px-tall
version, both new precons present in the live install's precon folder.

### 83. Stone/Wood Added as Dungeon Loot, Ratio to Gold — `Done (playtest-confirmed 2026-08-22)`
User spec: "go through all the dungeons, not towns, and add some stone or wood resource drops to
them. Maybe, let's say for each 2 gold drops there is, add one stone or wood. Min 1. (Just those
that don't already have these)". Also fixed, same round: the 11-POI missing-`Hostile`-tag gap
found while investigating the `Oasis` despawn report (see #15's update note above).

- **Scope**: every `.tmx` under `maps/map/` except `towns/` - 309 files. 77 already had a
  `stone.tx`/`wood.tx` object placed (skipped, per "just those that don't already have these") -
  231 edited, 287 objects added (39 files got wood, 192 got stone).
- **Count formula**: `max(1, goldCount // 2)` per file - a dungeon with 0-3 gold pickups gets
  exactly 1 stone/wood, 4-5 gets 2, 6-7 gets 3, etc. 80 of the 231 edited files had zero
  `gold.tx` objects at all (several are the new Realm of Legends boss dungeons, which reward
  through boss kills rather than pickups) - these still got their 1 via a fallback anchor
  (treasure/manashards/scroll/enemy/entry_up position, first one found, in that priority order).
- **Wood vs. stone assignment, by top-level `maps/map/` folder** (extending the existing
  `RewardData.shardsSubstituteType()` cave=stone/fort=wood rule, which only covers those two
  folders, to the other ~30 biome folders this plane actually has):
  wood = `fort, grove, evilgrove, barbariancamp, nest, hunting_lodge, garruk, catlair, grolnok`;
  everything else (`cave, aerie, demontower, djinnpalace, emrakul, factory, graveyard_crypt,
  jacetower, lair, lavaforge, magetower, maze, merfolkpool, minibosses, monastery, nahiri,
  naktamun, phyrexia, skep, skullcave, snowabbey, tibalt, vampirecastle, zedruu, main_story*,
  hostiletown`) = stone. These are judgment calls, not from user spec directly - flagged back to
  the user as adjustable per-folder if any read wrong thematically.
- **Placement**: each new object reuses an existing anchor object's `(x,y)` (a gold pickup, or the
  fallback object above) offset by a small `±5` nudge, rather than picking blind coordinates -
  guarantees the position is in a real, reachable, already-tested part of the map. Object ids
  assigned from each file's own `nextobjectid`, which is bumped to match afterward.
- **Template path**: plane-local (`maps/obj/stone.tx`/`wood.tx`, not `common/`, matching how the
  originals were added in #79), computed per-file from actual folder depth under `maps/map/`
  (1 level for most, up to 3 for nested cases like `cave/cave_multilevel/` or
  `main_story/castles/`) - verified against a real known-working precedent (`cave_multilevel/
  cave_16B.tmx`'s existing `common/` tileset reference depth) before running against the full set.
- **Same-round correction**: the first full run shipped with the `obj/` path segment missing
  entirely (`../../wood.tx` instead of `../../obj/wood.tx`) - a mismatch between the hand-tested
  dry-run script (which had the correct path hardcoded as a parameter) and the production script
  (which rebuilt the same string from a variable that turned out not to include `obj/`). Caught
  immediately by a Tiled screenshot arriving mid-turn showing the correct pre-existing wood.tx
  reference for comparison, then confirmed exactly via `grep` before touching anything twice.
  Fixed with a second, narrowly-scoped regex pass (matches only `template="(\.\./)+  (stone|
  wood)\.tx"` - a pattern the 77 already-correct pre-existing references can't match, since theirs
  always has `obj/` immediately before the filename) across all 231 affected files, then
  re-verified with a corpus-wide check: 0 XML errors, 0 unresolved `template=` references of any
  kind across all 332 files in the tree (not just the ones touched), 0 real object-id collisions
  (an initial "3 files with duplicate ids" alarm turned out to be harmless, pre-existing per-tile
  collision-shape sub-object ids scoped inside `<tile>` definitions, not the top-level reward/
  enemy objects that actually matter - confirmed against the original `common/` source file,
  which has the exact same "duplicates" and always has).

No Java touched - pure map-data changes, no compile needed. **Deployed 2026-08-18** alongside the
`Hostile`-tag fix - `robocopy /E /XO` of the plane's `maps/map/` tree (231 changed files) plus
`world/points_of_interest.json`.

### 84. Building Upgrades — `Not Started`
User wishlist addition (2026-08-18): tiered upgrades for existing economy buildings beyond the
Armory's own level system (#22) - e.g. Mines producing more per level, a bigger Bank/Exchange
tier. Needs its own design pass on which buildings, how many tiers, and cost curve.

**Named targets (user, 2026-09-01)** - the four things this item should actually deliver:
- **Mine Upgrades** - tiers that raise the weekly payout (`mineWeeklyGoldPayout`, currently a flat
  75 gold/week per mine in the plane's settings.json), presumably with Wood/Stone build costs.
- **City Walls** - a new defensive building. Note this is adjacent to #8 "Town Fortifications",
  which was REMOVED by user decision 2026-08-12; walls here would be a player-town construction
  rather than the AI-town fortification that item described, so it is a genuinely new design.
- **Mage War Camp** - a new building; purpose still to be defined (offensive staging for #87's
  attacking options is the obvious reading, and would tie the two items together).
- **Armory Upgrade** - extends the existing Level 1/2 system in #22 with further tiers.

### 85. New Quests — `Not Started`
User wishlist addition (2026-08-18): additional quest content beyond the existing story/side-quest
system (#16's timers, the main story chain). Scope (new story arcs vs. more side-quest variety
vs. both) not yet defined.

### 86. Additional AI Diplomacy Interaction — `Removed (2026-09-01, user decision)`
User wishlist addition (2026-08-18): more player-facing interaction with AI colors beyond the
existing Reputation/Territory Control levers (#1, #7) - e.g. direct negotiation, alliances,
trade offers. Needs a design pass on what "diplomacy" concretely means as a player action here.

**Removed 2026-09-01 (user decision).** Not pursued. The reputation system (#1) already gives every
color a standing that the player moves through play, and territory control (#7) gives the
consequences; a separate negotiation layer on top was never scoped and is not wanted. Kept here
rather than deleted, matching how #5, #8 and #26 were retired.

### 87. More Attacking Options — `In Progress (2026-09-03: Attack/Leave at a War-status AI town; win captures the town as a restored ruin; AI guard dots 1-4 by 28-day ownership set the defender tier, capitals two Archmage dots and unattackable; awaiting playtest; researched 2026-08-31, see STAR_TOWNS_RESEARCH.md)`
User wishlist addition (2026-08-18): expand the player's offensive options against AI
towns/castles beyond the current Territory Control capture mechanism (#7). Scope not yet defined -
could mean new attack types, mercenary/ally forces, siege mechanics, or something else.

**Researched but not built (2026-08-31).** `STAR_TOWNS_RESEARCH.md` in the repo root answers the
groundwork, every claim cited to `file:line` and independently re-verified:
- **The player cannot attack an AI town today** - confirmed, no siege mechanic exists in code or
  data. All 20 color-town maps contain zero enemy objects, and "siege" appears once in the whole
  adventure tree as an unimplemented enum value. The only route into a color's territory is
  wholesale: clear its castle boss, which reverts every town it owns to neutral ruins.
- **Giving the defending AI a head start already works.** `EffectData.startBattleWithCard` is
  applied to the AI side, and in a chained fight to every AI seat. Caveat: it lives on the sprite,
  not on `EnemyData`, so it is a `.tmx`-only channel and cannot be authored per catalog enemy.
- **1-vs-2 needs no new engine work** - see #98, unblocked in round 77.
- **AI end-game objective: capture the centre of the map (user, 2026-09-01).** This is now an
  explicit requirement of this item, not just a research note - the AI currently has exactly ONE
  way to win (taking the player's Capitol), and this gives it a second. The research below is the
  groundwork for it.
- The same document researches this idea in its built form: **five "star" towns around the central
  campfire as a genuine AI win condition** (the AI currently has only one way to win - taking your
  Capitol). Placement is achievable with `radiusFactor: 0` and no code at all, at a
  seed-tested-safe radius of 45 tiles, and the loss check has one obvious home. Its open design
  questions (which anchor the arms point at, starting ownership, win threshold, whether the player
  can retake one) are listed there and are the real blocker, not the code.

### 88. Post-Playtest Polish: Quiet Despawns, Tutorial Additions, Real Resource Sparkle, Two Log-Review Bugs Fixed — `Done (playtest-confirmed 2026-08-22)`
Two user messages in one thread: a request to quiet down routine dungeon-despawn notifications
and expand the opening tutorial dialogue, followed by a report that the newly-added Stone/Wood
dungeon pickups don't animate and don't show a preview icon in Tiled, plus a request to check the
logs from a multi-week play session for issues.

- **Routine despawn notifications removed.** "Has fallen - it fades from your maps" (loss) and
  "has been cleared out - it fades from your maps" (clear) no longer pop up - `DungeonRotation.
  onDungeonDefeat()`/`onDungeonClear()` now call `hidePoi(..., null)` for the ordinary,
  non-quest case. The side-quest "N attempts remaining" warning and "final attempt has failed"
  message are kept - those carry real information about a quest target being at risk.
- **Tutorial dialogue extended** (`world/quests.json`, the opening mage conversation) with the
  user's requested points: build a Capitol ASAP (needs 5 towns), Capitol-only buildings, cheaper
  cards in your own towns, don't lose your Capitol, and expansions unlock by finding + researching
  cards from lands that carry them - appended as a continuation of the same NPC's existing line
  rather than a new branching dialogue node, to avoid touching the surrounding quest-flag logic.
- **Real root cause found for two separate user-reported issues, both from the same mistake.**
  When building the original Wood/Stone pickup templates (#79), the "sprite" property was pointed
  at a single-frame atlas built by reusing an existing static icon, and no `gid`/`<tileset>` was
  set on the object at all - **without realizing a proper 4-frame animated sheet already existed**
  (`sprites/wood.atlas`/`stone.atlas`, both reading `resource_drop.png`, user-provided
  2026-08-13, the same sheet Gold/Shards already use). `CharacterSprite.load()` animates whatever
  region set shares the name `"Idle"` - one frame animates as a static image, which is why nothing
  visibly twinkled. Separately, every OTHER reward template (`gold.tx`, `treasure.tx`,
  `manashards.tx`) declares a `<tileset>` + `gid=` specifically so Tiled has something to render
  as an editor preview - mine had neither, hence the blank box in Tiled screenshots. **Fixed**:
  repointed both templates' `sprite` property at the real animated atlases, added a `gid`
  reference (a rock icon for Stone, a bare tree for Wood, both pulled from the same
  `common/maps/tileset/buildings.png` sheet Gold/Treasure already use) matching the established
  house convention, and deleted the now-dead single-frame atlas files. Since all 308 placed
  instances (77 pre-existing + 231 added in #83) reference these two template files rather than
  embedding their own copy, this one fix covers every instance in the game at once - nothing else
  needed touching.
- **Log review turned up 3 real findings**, not just the twinkle/icon issue - see the
  twenty-fourth changelog round for the full log-review methodology:
  - **A real, reproducible crash-adjacent bug on every New Game**: `PointOfInterest`'s
    constructor (`PointOfInterest.java:77`) divides by an empty sprite array's size with no
    guard, throwing `ArithmeticException: / by zero` (plus two follow-on NPEs in the minimap/
    chunk-math code) whenever a POI's sprite lookup comes back empty. Root cause: the "Ashling's
    Domain" POI added in #82 references the `"Ashling Domain"` region in `Realm of Legends/
    sprites/buildingsbosses.atlas` - a region that's real (present, unchanged pixel data, in the
    2.0.15 snapshot this content was ported from) but was missing from this repo's own copy of
    that atlas file, which had never been resynced with the 2.0.15 update beyond the specific
    content actually ported. **Fixed** by updating the repo's `buildingsbosses.atlas` to match
    the 2.0.15 snapshot exactly (confirmed the underlying PNG is byte-identical between versions
    - this was a pure text-index update, zero risk to existing art). Also checked `buildings.atlas`
    (used by 3 other new POIs) for the same staleness - it also differs from the snapshot, but the
    specific regions actually referenced (`Building198`/`41`/`56`) are byte-identical in both
    versions, so no fix was needed there.
  - **A real gap in `DungeonRotation.hidePoi()`**: no guard against being called twice for the
    same POI, which the log showed happening repeatedly - a combat-triggered despawn
    (`onDungeonDefeat`/`onDungeonClear`) and `processDaysPassed()`'s own natural-expiry check can
    both fire for the same POI on the same day with no coordination between them, each rolling a
    fresh random respawn day and silently discarding the other's roll (harmless in practice - just
    wasted a re-roll, no visible player-facing effect - but a real gap all the same). **Fixed**
    with an early-return guard: a second call on an already-inactive POI is now a no-op.
  - **One finding investigated and left open, not confidently a bug**: the Fog-of-War "stage 2"
    exploration counter (`[TFR-FoW] stage2 check`) sat frozen at exactly 2542/288607 (0.9%) across
    135 log lines spanning roughly day 18 to day 156+, while Territory Control radius grew
    normally over the same span. Traced the mechanism: `World.revealPlayerOwnedTiles()` (which
    would sync territory growth into fog-of-war exploration) is only ever called from
    `TownRestoration.repairAllTownVisionReveal()`, a load-time repair pass - not from any
    periodic/daily tick. Suspicious (an exact freeze across many real-time days, not just slow
    growth), but not confirmed as a bug rather than a real gameplay pattern (the player may
    simply not have walked into much new territory on foot during that stretch, since fog-of-war
    exploration and Territory Control ownership are two separate systems). Flagged for the user
    rather than guessed at with a speculative fix to a system not fully understood in one pass.

Deployed 2026-08-19 on the user's go-ahead; playtest in progress.

### 89. Update to Forge 2.0.15 + Standalone Game Packaging — `Done (2026-08-19): merge + package smoke-tested by user; release-gate sweep fixed 20+ content/engine issues; v1.00 pushed to GitHub + zipped; final playtest round 2026-08-20 (welcome-popup fix, time docs/promo, Eldrazi icon, ante UX, capitol pickups, Oasis, prices, tunable quest-days/mage-base) applied - see twenty-eighth round`

User request (2026-08-19): "We build our mod from an older Forge update. We're on 2.0.14-08.02 and
the latest build is 2.0.15-08.19. ... We need to update all our files to the new version ... and
keep our edits. Next we need to package everything up as a stand-alone game. (Let's not consider
this a MOD anymore, but it's own game.)" Plus: credit the Forge dev team and the Realm of Legends
and Shandalar Old Border mod teams; all needed licensing in the game folder. Do NOT touch the live
install at `E:\GAMES\FORGE` (still being playtested on the 2.0.14 build) — repo only.

**Part 1 — upstream merge: DONE 2026-08-19 (repo-only, deliberately not deployed).** All prior
work committed first as `d6b5caea863` and tagged `tfr-v0.9-base-2.0.14`; then upstream `master`
@ `06019e99eed6` (the exact commit of the user's 2.0.15-08.19 snapshot install at
`E:\GAMES\Forge_2`) merged in. Two textual conflicts, both hand-resolved; full compile clean;
12-agent verification pass confirmed every mod edit survived. Details in `MOD_CHANGELOG.md`
(twenty-fifth round) and `CORE_ENGINE_CHANGES.md` ("Upstream merge log").

**Part 2 — standalone packaging: decisions locked with the user 2026-08-19:**
- Own complete game folder, pinned to this build; players unzip anywhere and run. Stock Forge
  installs stay completely separate.
- Slim: delete other adventure planes so TFR is the only world; KEEP `common/` and the full card
  database/editions. Requires an asset-closure audit first — TFR references
  `Realm of Legends/sprites/buildingsbosses.atlas` + `buildings.atlas` at runtime (4 POIs each),
  so those must be migrated into the TFR folder (or kept) before RoL can be deleted.
- Data isolation via `forge.profile.properties`: OWN `userDir` (settings/saves/prefs) so nothing
  collides with stock Forge, but SHARED card-art cache (`cardPicsDir` pointed at stock Forge's
  pics folder) — the user explicitly doesn't want players re-downloading gigabytes of card art.
- Rebrand as its own game (window title, launcher, icon identity); Forge credited per GPL.
- Strip/disable the update checker so nobody half-updates a pinned install.
- README + CREDITS (Forge dev team, Realm of Legends team, Shandalar Old Border team) + GPLv3
  LICENSE in the package; public GitHub fork push at release time satisfies the GPL source
  obligation (GitHub untouched until the user says so).
- Each public release = git tag + zip, always rebuildable.

### 90. Trader Building — `Done (playtest-confirmed 2026-09-01)`

User request: a cheaper, earlier-game way to convert gold into Wood/Stone than the Capitol-only
Exchange, buildable in any ordinary town. Built as a new Economy Building type alongside
Bank/Exchange in the Financial submenu - same Buy-5/Sell-5 mechanic as Exchange, Wood/Stone only
(no Shards), at rates 25% worse both directions (Buy 125g / Sell 60g vs. Exchange's 100g/80g).
200 gold flat to build (gold-only by design - it would be backwards to charge Wood/Stone to build
the thing that sells you Wood/Stone).

**Upgrade to Exchange**: a Trader built specifically at your Capitol shows an "Upgrade to
Exchange" option (pays Exchange's own build cost on top, same as it costs to build one fresh -
matches the Armory/Arena Level 1→2 precedent of upgrades costing extra, not being free). A town
Trader has no such option and keeps working as a Trader indefinitely - each location's building is
independent, nothing carries over or migrates between towns (user's own choice among three
proposed designs, see `MOD_CHANGELOG.md`'s "thirty-third round").

### 91. Tier 2 Enemy-Balance-Curve Reconciliation — `Done (2026-08-22)`, Eldrazi Prison import included

Follow-up to #89's Realm of Legends port. Tier 1 (v1.01) only *labeled* the 9 ported "Legendary"
POIs as endgame content (minimap glyph + entry warning) without touching their actual numbers,
which still assumed RoL's 30-starting-life balance rather than TFR's own (Easy 21/Normal 16/Hard
11/Insane 9). Tier 2 is the real numbers pass, using **20** as the reference life for this pass
specifically (user's deliberate choice - splits the difference between RoL's 30 and TFR Normal's
16 for endgame-tier content).

**Audit delivered, then applied, same day (2026-08-22)** - report in `MOD_CHANGELOG.md`'s
"Thirty-fifth round": a ×2 life rescale for 39 of the 39 ported bosses (rescaled 20/30/40/50 tiers
onto TFR's own native boss range: 40/60/80/100), and rarity assignments for 12 of the 14 items
those bosses drop (the other 2 already fine) - closes the item-rarity gap to 0/642 missing. Both
**applied directly to `enemies.json`/`items.json`** the same round (verified: all 39 life values
and 12 rarities landed correctly, both files re-validated as clean JSON).

Two things a numbers pass alone couldn't fix, also resolved same day:
- **Eldrazi Prison had no reachable boss at all** - a known, deliberately-scoped 2026-08-10 gap
  (only 1/8 of the dungeon was ever built) that never got reconciled against its own later
  "Legendary" promotion. **Fixed via a full import** (not a stopgap): all 6 titan boss chambers
  plus a previously-unlisted 7th (`Hall_of_the_Unifier.tmx`, RoL's own true final encounter - Jodah
  the Unifier - found on inspection, not assumed away) copied in from `Realm of Legends`; the "Six
  broken cross-plane dungeon exits" path bug fixed for all 7 new files and the hub's 7 doors
  re-enabled; the 12 gate items restored to `items.json`; a real `Emrakul`/`Kozilek` name collision
  against pre-existing baseline bosses resolved by adding two distinctly-named entries (`Emrakul,
  the Aeons Torn` / `Kozilek, Butcher of Truth`) rather than overwriting the already-live baseline
  ones; reachability traced end to end, including where the Pentakey Shards/Ur-Dragon's Key
  actually come from (6 other roaming legends elsewhere in the world, not this dungeon). The 6
  titans' own life totals were deliberately left as imported (already sit inside TFR's native
  range, unlike the 39 dungeon-exclusive bosses above). Full detail, including what's still
  unconfirmed (no in-game walkthrough, no pixel-level tile-collision check), in
  `MOD_CHANGELOG.md`'s "Thirty-sixth round."
- **Zo-Zu the Punisher** (life 1, zero rewards, reads as a joke cameo unrelated to the Dueling
  Club's real 6-fight gauntlet) - left untouched, no change requested.

Not yet packaged/deployed for testing as of this entry - repo-only.



### 92. Shop Type Blueprints — `Done (rounds 71-76, round-78 fixes, round-80 tier gate) - not yet playtest-confirmed`
Backfilled 2026-09-01. The largest feature built since v1.03, and the one this file was missing
entirely. Shop types are no longer just whatever a town slot happened to roll: each type must be
**unlocked** before the player can build it, and unlocked types are chosen deliberately.

- **Unlock currency is a blueprint.** Found as a drop (Mystery/Chest pickups, and from round 76 the
  Archaeologist at 15% per expedition, see #24), or bought at a shop's Buy Blueprint button.
- **Reputation ladder on purchases** (user spec): Rare types need Partner standing, Uncommon needs
  Happy, nothing at all is sold below Neutral. Cost scales on the SAME standing -> multiplier table
  card prices use, so the two can never drift: 14/28/70 at Partner, 17/34/85 at Happy, 20/40/100 at
  Neutral. Player-owned towns are exempt (no color is selling you anything); Neutral/Spawn towns
  have no standing, so base price and no gate.
- **One type per town** (user spec). The chooser sorts Available -> Built -> Locked, alphabetical
  within each group, and shows built/locked entries grayed and labelled rather than hiding them -
  hiding a type reads as "that type does not exist here". Enforced on the random re-type path too
  (see #32), so a destroy-and-rebuild cannot slip in a duplicate the chooser would have refused.
- **Cartographer land shops are outside the system entirely** (user spec, round 73), keyed on
  `ShopData.sprite == "LandShop"` - the 5 basics only; the twelve nonbasic land shops are untouched.
- Blueprint drops are revealed as a card you turn over (`Reward.Type.Blueprint`), not a HUD line
  that scrolls past unread.

Three bugs of note along the way, all fixed: shops whose identity changed only half-applied until
you left and re-entered the town (round 72); `MapStage`'s five shop registries leaked between towns
because they key on tmx object ids that every town reuses (round 73 - see the "Recurring root cause"
note in `MOD_CHANGELOG.md`); and the whole ladder silently no-opped in the 5 AI capitals, which
declare a flat `shopList` with no tier lists, until a global shop-name -> tier map was added.

**GAP CLOSED IN ROUND 80 (2026-09-01)** - `FLAT_TOWN_SHOP_TIERS`, a static shop-name -> tier table
derived from `player_town.tmx`/`player_capital.tmx` (the templates that decide what a blueprint
actually buys you), now sits between the slot's own pools and `globalShopTiers` in `shopTierOf()`.
It deliberately OUTRANKS the accumulator, because round 80 found a SECOND bypass the note below
missed: `registerShopTiers()` uses `putIfAbsent`, so the first map visited wins forever, and "White"
is Common in a generic White town but Rare in the player's own - a fallback placed below
`globalShopTiers` would never have fired on that route. `auditFlatTownTierFallback()` re-derives the
table from its source on every player-town map load and logs drift. Round 80 also corrected two
errors in the description below: the flat-`shopList` maps are NOT the AI capitals (all five
capitals carry full tier lists) - they are the ORDINARY color towns, 250 of the plane's ~500, so
the bypass was the default path rather than an exotic one.

**The original gap, as found by the round-79 audit (kept for the record):** The headline user spec - "can't
buy a Rare blueprint unless you are at Partner" - can be bypassed. `globalShopTiers` is a per-process
in-memory accumulator populated only by maps that declare `commonShopList`/`uncommonShopList`/
`rareShopList`. Five live town templates (`plains_town.tmx`, `island_town.tmx`, `forest_town.tmx`,
`mountain_town.tmx`, `swamp_town.tmx`) declare a flat `shopList` instead. Their card-shop slots still
have a candidate pool, so the Buy Blueprint button appears - but `shopTierOf()` finds no tier, and
`blueprintStandingBlock(null)` skips BOTH the Rare-Partner and Uncommon-Happy branches while
`blueprintShardCost(null)` charges the Common 20. So on a freshly launched process, going straight to
one of those five towns sells a Rare blueprint for 20 shards with no standing requirement. Entering
the Capitol (or any `_generic`/`_identity`/`_tribal` town) first populates the map and the gate works
correctly. Not save-corrupting, and it errs in the player's favor, so play will never report it.
Fix options, both small: seed `globalShopTiers` from the plane's town templates at world load, or
give `shopTierOf()` a static name -> tier fallback. Deliberately deferred rather than changed on
release eve. (Related: the comment above `allChooserShopNames()` claims the universe is built from
"the plane's town templates" - nothing reads templates; that stale comment helped hide this.)

### 93. Bronze Coin Ante Ransom — `Done (playtest-confirmed 2026-09-01)`
Backfilled 2026-09-01. A recoverable insurance item against a bad ante. Lose a duel while holding a
**Bronze Challenge Coin** and you may hand it to the victor: every card you anted this duel comes
back and the defeat's gold penalty is waived (life loss still applies). Beat that same enemy later
and you take the coin back.

- Ordinary duels only - never in Inn tournaments or Arena brackets (those have their own entry-fee
  economies), and never against a boss, so it cannot trivialize a set-piece fight. Reclaiming is
  allowed anywhere, including the Arena: beating the enemy holding your coin should return it
  wherever that rematch happens.
- **One coin per enemy** (user spec, round 73). A second coin paid to the same enemy used to be
  silently swallowed - two losses to one fox cost two coins and returned one.
- Available as a Mythic Armory item at **1,000 gold** (Mythic rarity from round 68; priced 15,000
  that round, retuned to 1,000 in round 69 and confirmed intended 2026-09-01), and marked on the
  player statistics
  page next to any enemy currently holding one (round 76).
- **Reclaiming is a loot tile** (user request, round 77): the coin appears as a card on the win's
  reward screen rather than appearing silently in the inventory. Arena pays it out with the final
  bracket loot.
- **PLAYTEST-CONFIRMED 2026-09-01.** User tested both coin flows end to end - the Bronze Coin
  reclaim-as-loot on a duel win, and the Inn tutorial Coin refund - and both behave correctly. That
  same test also confirms round 80's dialog fixes, since the refund dialog is what exposed the
  hard-quit soft-lock: the body now wraps inside the screen and dismissing it no longer resurrects
  an empty modal window. The Arena bracket payout path has NOT been separately exercised.

### 94. Timed Armory Rarity Gating — `Done (built 2026-08-31, round 75), not yet playtest-confirmed`
Backfilled 2026-09-01. Extends #22/#33: what the Armory can stock is gated by how long the run has
been going and by where the shop is. No Rare anywhere in week 1; no Mythic until week 3, and then
only in the Capitol; player towns catch up at week 4, when the Capitol also sharpens to 45/35/16/4.
Neutral towns never sell Mythics.

Expressed as ONE weight table (`config tables/armory_rarity.json`), not a gate plus an odds table,
because a banned rarity is simply a zero weight. That means no Armory slot is ever dropped - unlike
the old post-generation Mythic strip, which left a hole - and the seeded weekly stock stays
reproducible because it is still one RNG draw per slot. AI capitals are unaffected: their Armory
shops use hand-written fixed item lists and never roll a rarity at all.

### 95. Per-Color Capitol Attack Cooldown — `Done (built 2026-08-31, round 75), not yet playtest-confirmed`
Backfilled 2026-09-01. Extends #7. Each color may target the player's Capitol at most once per 7
in-game days (`capitolTargetCooldownDays`, rolling window, 0 disables). Stamped at **dispatch**, not
at resolution, so it counts "regardless if the mage wins, loses, gets killed" per the user spec -
a mage walks to its target over several days and can be duelled en route, so a resolution-time
stamp would let a color re-target while its first mage was still on the road.

### 96. New Game+ Is Now Genuinely a New Game — `Done (built 2026-08-31, round 74), not yet playtest-confirmed`
Backfilled 2026-09-01. User report: "I started a NG+ and it seems none of the shop mechanics are
working... with NG+ everything is unlocked." Root cause: the NG+ path deliberately loads an existing
save to keep the collection, and therefore never called `create()` - where every per-run field is
seeded. An audit found the same gap in nine places, so the fix addresses the class, not the
instance: shop-type unlocks, edition unlocks, research timers, character flags, color reputation,
coin-ransom marks, events, blessing, and the player/AI edition exclusivity pass are all re-run now.

Deliberately carried forward, and logged at reset time so a future bad edit shows up in `forge.log`
rather than in a player's save: cards, decks, inventory, equipment, boosters, all four currencies,
max life, name/race/avatar. Known accepted cost: an in-progress draft/sealed tournament is
discarded, exactly as a New Game already does.

### 97. Android Release — `Blocked on a device/tester, not on code - v1.03 APK shipped 2026-08-27; v1.04 version fields pre-bumped, no v1.04 APK built`
Backfilled 2026-09-01. Signed APK plus a paired assets.zip, attached to the `tfr-v1.03` release and
marked experimental/community-test. **`ANDROID_RELEASE.md` in the repo root is the authoritative
per-release procedure** - read it before any Android work; it carries the keystore rules (the SAME
key must sign every future APK), the cmd.exe command-length workaround, the APK/assets.zip
same-build pairing rule, and the upstream-merge revert-watch list. Portrait-layout fixes for phone
screens followed in rounds 69 and 70.
- **Status as of 2026-09-01 (round-79 audit).** Round 78 bumped `tfr.version` to 1.04 and
  `manifestVersionCode` to 10400 - `ANDROID_RELEASE.md` steps 1 and 2, matching `config.json`'s
  modVersion 1.04. Nothing else has happened: `forge-gui-android/target/` still holds only the
  1.03 APK and assets.zip from 2026-08-27, there is no `tfr-v1.04` tag, and step 0 (cut the desktop
  release first - the APK attaches to that same GitHub release) is not done either. This item cannot
  move past "shipped experimental" without a tester, since the user has no Android device.

### 98. Multi-Opponent (1-vs-N) Duels — `Engine support Done (round 77); content Not Started - zero reachable 1-vs-N fights exist`
Backfilled 2026-09-01. Research finding, not a new build: `EnemyData.nextEnemy` has ALWAYS built a
real simultaneous multiplayer match (up to 1-vs-8, full Forge N-player rules, multi-opponent-aware
AI, dedicated 3- and 4-player match layouts). "Goblin Pack" is the only entry in 1,520 TFR enemies
and 464 common enemies that uses it.

It went unused because opening a chained duel **crashed**: 491 of the 493 enemy atlases carry
exactly one avatar frame, and the duel scene asked for one per seat. Round 77 fixed that (clamp,
plus a null guard for the one atlas with zero avatar frames, plus per-seat nameplates and a
shared-sprite flip bug). The feature is now reachable with no data-file changes - setting
`nextEnemy` on a cloned `EnemyData` is the entire "make this a 1-vs-2".

Two known costs before leaning on it: only the head enemy's rewards pay out, and `teamNumber`
defaults to -1, which silently makes a chained fight a three-way free-for-all instead of you
against a team. Set it explicitly on every seat. Full survey in `STAR_TOWNS_RESEARCH.md` Part 4.

**Split into engine vs content 2026-09-01, after the round-79 audit parsed the shipped catalogs
rather than grepping them.** The ENGINE half is done and verified. The CONTENT half is empty, and
more comprehensively than previously understood: of 1,520 TFR enemies and 464 common ones, exactly
one (Goblin Pack) has a `nextEnemy` chain - and **Goblin Pack is in no biome spawn list**, appearing
in the whole plane only in `world/enemies.json` and one content-filter row. Innistrad, Realm of
Legends and Shandalar Old Border have zero chained enemies. So there is currently **no way to
encounter a 1-vs-N fight in normal play at all**, and round 77's crash fix, while correct and
necessary, cannot be observed by playing. Making this reachable means authoring content - the
obvious home is #87's town assault, where a defended town is the natural set-piece.

### 99. Roaming-Spawn Declustering — `Done (built 2026-09-01, round 77), not yet playtest-confirmed`
Backfilled 2026-09-01. User report: "3 Khenra Warriors close to each other." Nothing was broken -
the biome enemy pick is a memoryless weighted draw, so a common entry naturally comes up several
rolls running, and no code had ever looked at what was already on screen.

Measured before tuning: across 251 roaming spawns in one session's log there were 18 runs of the
same enemy twice in a row and exactly **3** runs of three in a row (Khenra Warrior, Fox, Falcon) -
matching the user's "3 instances" precisely. Pairs are constant and read as normal; triples are what
reads as broken. So a fresh roll of an enemy that already has 2 of itself alive within 220 world
units of the player is re-rolled, up to 4 times, then spawned anyway - deliberately a re-roll and
never a skipped spawn, since refusing to spawn would silently thin the world wherever a biome list
is short. War-tier bosses and quest-tag extra spawns are authored encounters and are left alone.

### 101. Resource-Drop Placement Sweep (the +5/+5 cluster bug) — `Done (2026-09-02, round 85)`
**Closed 2026-09-02 (round 85):** a full scan of all 339 maps found the remaining 50 plus 18 more on the other three diagonals (the first scan only looked at +5/+5) and 26 non-clustered drops buried 25-100% in collision; all 94 relocated by script onto verified open floor, and the same scan fixed 11 broken door links, 50 mis-keyed card rewards, three mistyped boosters, a misspelled item, a broken effect string and a broken dialog. Detail in MOD_CHANGELOG round 85; four design questions left there for the user.

Added 2026-09-01 from a mod-wide audit of the user's own dungeon pass. A large number of stone/wood
resource drops were authored sitting at **exactly +5.000px / +5.000px** from another object - a
bulk-placement artifact, not a design choice. The offset is identical to three decimal places
across every instance, which is what identifies them.

Scanning all 339 `.tmx` files (722 stone/wood objects) found **75**. The user hand-fixed **25** on
2026-09-01 across the cave_16 / cave_18 / cave_21 chains, dragging each onto open floor. **50
remain in 49 other maps**, and they are not new - they have shipped in every release to date
including v1.03, which is why this was scoped as a follow-up rather than a v1.04 blocker
(user decision).

Severity split of the remaining 50, for triage:
- **2 are out of bounds** and cannot be collected at all: `minibosses/camelboss/entrance.tmx` stone
  id 66 at (214,277) - 5px below a 272px floor, and byte-for-byte the same bug as the `cave_16.tmx`
  instance that WAS fixed, because it is a duplicate of the same base map; and
  `main_story/temple_of_liliana/bog.tmx` stone id 273 at (357,597) on a 592px-tall map.
- **15 are partially buried in collision geometry.** Worst: `main_story_defend/
  waste_town_abandoned.tmx` id 118 (52% buried), `grolnok/grolnok_f1.tmx` id 211 (48%),
  `graveyard_crypt/graveyard_5.tmx` id 89 (36%), `graveyard_crypt/crypt.tmx` id 86 (31%). All five
  castle main-story maps and all five castle_f1 maps have one each.
- **33 are on open floor but stacked** on another pickup or an enemy - cosmetic, still collectable.
- Note `cave/cave_multilevel_3/cave_21C.tmx` id 85 is among the 50 *despite* being edited in the
  2026-09-01 pass - a new stone (id 86) was added to that file while the clustered one was left.

Unrelated to #99 (Roaming-Spawn Declustering), which is overworld enemy draws, not static pickups.

### 100. Post-v1.03 Fix Rounds (62-79) — `Ongoing; all local-only, none released - closes when v1.04 ships`
Backfilled 2026-09-01, as a pointer rather than a re-listing. Everything since the v1.03 release has
been local-repo work awaiting a test pass - sixteen rounds of playtest fixes and the features above.
The per-round engineering detail lives in `MOD_CHANGELOG.md` (rounds 62-77) and every engine-file
edit in `CORE_ENGINE_CHANGES.md`. Highlights not already given their own item above: save/load state
bleeds traced to three app-session singletons, the inventory crash, tournament stat double-counting,
the day-end freeze, chest reworks, the "Raise the Banner" main-quest rework and Forsaking backstory,
the spawn-dialog rebuild (which fixed a New Game+ branch that silently deleted the whole main story
from a save), ruined-town Inn rules, and the restored Green capital equipment shop. Round 78 was a
six-lens pre-release code review (14 confirmed defects fixed, including a save-integrity blocker)
and round 79 this scope pass plus the Skip Tutorial dialog fix. This item closes when v1.04 is
actually released; until then "none released" stays literally true.

### 102. Center Towns / Ring Cities - the Star around the campfire — `In Progress (first cut 2026-09-03, new worlds only; awaiting playtest)`
User spec 2026-09-03: five towns in a star around the campfire, roads radiating from the fire to
each, one arm pointing at each AI capital; the user's own castle art per owner color
(`Center_Town_<color>.png`, neutral by default); ordinary neutral towns for targeting, capture,
restoration and guard hiring; **the player loses when any one AI color holds three of them**.
Built in round 95 on the STAR_TOWNS_RESEARCH.md placement math (R = 45 tiles, `radiusFactor 0`).
Open: bespoke interiors ("will need to build an actual inside later"), a tighter no-neighbors
rule around the arms (placement only guarantees the 8x8-tile box), and whether the star towns
should ever seed as functioning neutral towns.

### 103. Main quest opening - "Oaths at the Ring" — `In Progress (first cut 2026-09-03, awaiting playtest)`
The five Ring Cities (Benalia, Tolaria, Urborg, Shiv, Llanowar) woke the Guardian after letting the
Five loose through the Seals they were meant to keep. Tutorial: start with nothing, walk the Ring for
the difficulty's gold / shards / wood / stone / items and +1 life per free city, then the old chain
(quest 30 onward). Open: full-screen defeat/victory scenes; the councils' "the Seals go back on"
thread as an end-game beat; per-city interiors.
