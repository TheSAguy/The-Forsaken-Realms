# XP / Leveling for The Forsaken Realms - findings, options, recommendation

*Design doc, 2026-10-08. **The user picked Option B with changes - section 7 is the live plan and overrides sections
4.4-4.6 where they differ.** No code written yet.*

Paths below: **J** = `forge-gui-mobile/src/forge/adventure/`, **P** = `forge-gui/res/adventure/The Forsaken Realms/`.
"Stock" = a file that exists in upstream Forge (each edit needs a `CORE_ENGINE_CHANGES.md` entry); "mod" = mod-added.

---

## 0. The short version

- **Recommendation: Option B, "Ascendance".** XP ("Power") reclaims the power the Five stole from you - the main quest's
  own premise. You gain a level (Ascendance 1-30). Each level gives either a **Boon** (pick 1 of 3, HoMM-style, mostly
  adventure-side perks) or a **Spoil** (pick 1 of 3 cards). Every 5 levels also gives a title. **The cap is bound by the
  five Seals**: you can reach Ascendance 10 freely, and each Seal you take back raises the cap by 4. XP earned while capped
  is banked, not lost.
- **XP pays for meaningful wins.** It scales with enemy rank, a first win against an enemy pays double, bosses, legends
  and champions pay more, and enemies you have **outgrown** pay less (down to 20%). About 40% of XP comes from things
  that are not duels: quests, first visits, clears, restored towns, treasures and the Seals.
- **It never makes the world harder.** The world's difficulty stays on its two existing axes: the calendar week and
  the hidden win-count rank. Ascendance is purely the reward axis. That avoids the Final Fantasy VIII trap of grinding
  making fights harder.
- **Pacing:** level 10 comes at about 60 wins, level 18 at about 160 and level 30 at about 320. That is close to a full
  run and about 40 hours at 8 minutes per duel. Every number lives in one config table, to be tuned with an agent soak.
- **Work:** about 5-6 rounds for B. About 2 for the light Option A. About 8 for the per-color Option C.

---

## 1. Phase 1 - what the game already has

### 1.1 Progression and reward channels (no XP or level exists anywhere today)

| Channel | Where | Notes |
|---|---|---|
| Gold / shards / wood / stone | J`player/AdventurePlayer.java` `addReward()` :2153, `addGold/addShards/addWood/addStone`; `win()` :2475 (+1 shard per world or map win) | Earned from duel loot, mines, pickups, quests, chests. Spent on restores, buildings, blueprints, research, ante, guards and digs. A defeat costs 2-15% of carried gold. Values come from P`config.json` and P`config tables/settings.json` (read into TuningData). |
| Cards / collection | `CardBudget` (first win vs an enemy pays 2/3/4/5 cards by rank, repeats 1/2/2/3), `ResourcePurse` (first win x1.5) | Already a "bestiary" bonus for first wins. |
| Deck slots | `maxNumberOfDecks` = 50 in config.json | **Not a progression channel**: every slot exists from the start. |
| Unlock ladders | `unlockedEditions` (research, own 10% of a set), `unlockedShopTypes` (blueprints) | Paid with resources and reputation. |
| Items / equipment | 13 slots (Left2 and Right2 granted by gauntlets: `grantedEquipmentSlots()` :3060). J`data/EffectData.java`: `lifeModifier`, `changeStartCards`, `startBattleWithCard*`, `extraManaShards`, `moveSpeed`, `goldModifier`, `visionRadiusMultiplier`, `cardRewardBonus`, `opponent`. Applied in J`scene/DuelScene.java` `applyEffects()` :736 | Items crack on a defeat. Shop rarity is gated by week (`armory_rarity.json`). |
| Max life | `addMaxLife()` :2632. +Life enemy rewards are paid once per game per enemy (`PlaceRewards.payLifeOnce`, 52 enemies). `townLifeBonus` (+1 per 5 towns, +1 Capitol), `ringLifeBonus` (+1 per free Ring City) | **The only permanent stat growth.** Start 20/15/10/5 by difficulty. A defeat costs 10-30% of max life. New Game+ resets it. |
| **Hidden rank** | J`player/PlayerStatistic.java` `rank()` :33. Lifetime wins &lt;20 = 0.5, &lt;60 = 1, &lt;=150 = 2, more = 10 | **Effectively a hidden 4-step level.** It only makes the world harder (it filters the enemy catalog by `difficulty`; readers include `BiomeData.getEnemy`, `SpawnTierWeighting.effectiveRank` with +1 step at a 30-win streak, dungeon upgrades, invasion leaders, pillage raiders). Never shown to the player. |
| Notoriety / per-enemy streaks | `notorietyStreak`, `recordNotoriety()` ~:436, `recordWinStreak` :356 | 8 levels of **penalty only**: Walls for enemies, +25% enemy life at 45/50, spawn tilt, Wastes after repeated wins. |
| Reputation | Color: `ColorReputation` (sums to zero, tiers Partner to War drive prices, entry, tolls, blueprints). Town: `PointOfInterestChanges.addMapReputation` (3 buildings per point, +1% defense per point) | Rich, and already the "standing" axis. |
| Towns / Capitol / guards | `TownRestoration`, `EconomyBuildings`, `RoamingGuards` (guard tier is fixed at hire and never improves) | Town leveling is planned but not built (MOD_SCOPE line 192). |
| Quests | Rewards are paid by dialog actions in P`world/quests.json` (`grantRewards`, `addGold`, `addMapReputation`). `AdventureQuestData.reward` is dead code | Main quest: 5 Ring Cities + 5 castles (the five Seals, quest 52). |
| Arena / Inn / treasure / legends / lairs / champions | `ArenaScene`, `EventScene`, `TreasureHunt`, `LegendSpawns`, `DungeonRotation`, `CaveChampions`, `WarChampions` | Big one-off payouts. |
| Difficulty | P`config.json` difficulties: startingLife 20/15/10/5, enemyLifeFactor 0.8/1/1.5/2.5, rewardMaxFactor 1.5/1/0.5/0, sellFactor, lifeLoss 0.1/0.2/0.3/0.3, spawnRank 0-3 | |
| New Game+ | `SaveLoadScene` :397, then `resetForNewGamePlus()` / `applyNewGamePlusCarry()` | Cards are always kept; resources and items are optional. Stats, rank, notoriety, reputation, max life and the world are reset. |

**What already behaves like leveling:** the hidden rank (a world-difficulty ladder), notoriety (a penalty ladder), max
life (the one permanent stat), research and blueprints (unlock ladders), and the first-win bonuses (a bestiary). **What
never grows:** deck slots, guard ranks, equipment slots (apart from gauntlets). **Enemy strength grows with the calendar,
not with the player** (`spawn_tier_weighting.json` `weekBrackets`: week 1 is 90% Apprentice, week 21+ is 22/24/30/24).

### 1.2 Player state and saving

- **Top level:** `WorldSave.save/load` writes the sub-records `player` (`AdventurePlayer.save()` :1907 / `load()` :1354),
  `world`, `worldStage` and `pointOfInterestChanges`. PlayerStatistic is saved as `statistic`.
- **Model for a new field:** `notorietyStreak` (round 411). It touches five places:
  - the declaration :782
  - `clear()` :730
  - `resetForNewGamePlus()` :1015
  - `save` :1956
  - load with a `containsKey` guard :1858
- **Rules:**
  - Store only primitives, Strings and lists of them; never a new Serializable class (the round-90 wipe).
  - Never add fields to `AdventureQuestData`/`Stage`.
  - New Game+ calls neither `clear()` nor `create()`, so every new field needs an explicit New Game+ decision.

### 1.3 The hooks an XP system needs

| Event | Hook | Notes |
|---|---|---|
| **Any duel's result** | J`scene/DuelScene.java` `afterGameEnd()` :382 | **The single funnel.** It knows `enemy`, `isArena`, `eventData` (Inn), `guardDeck` (guard fight), `aiControlsPlayerSide`, `fixedDeck` (Deck Tester). Award here, not in `recordStatistics`: guard fights count toward player wins there (round 166), and the stages' deferred win follow-ups are cancelled by a load (round 126). |
| Enemy facts | J`data/EnemyData.java` `tierRank()` :186, `boss` :30, `legend` :65; J`character/EnemySprite.java` `territoryColor` :155, `legendExpiryDay` :170, `championLoot` :158, `pillageTown` :176 | Town assault and Capitol defense are private WorldStage flags: they need a getter. |
| Quest completed | J`util/AdventureQuestController.java` `showQuestDialogs` :366-373 | `storyQuest` and `getID()` are available. |
| First visit to a place | `PointOfInterestChanges.isVisited()` :552, checked before `visit()` in `WorldStage.handlePointsOfInterestCollision` :1119 | Fog is per tile; there is no per-place "discovered" flag. |
| Place cleared | J`util/DungeonSources.java` `onCleared` :406 (once per incarnation; already pays reputation) | Lairs: `DungeonRotation.onLairExit` :261. |
| Town restored / captured / Capitol | J`util/TownRestoration.java` `recolorTerrainForTesting()` :595 (the real restore path, despite the name), `captureTownForPlayer` :476 | |
| Treasure / pillage / invasion / arena / Inn | `TreasureHunt.onGuardianBeaten` :686, `TownPillage.onRaiderBeaten` :379, `InvasionQuests`, `ArenaScene.setWinner` :856 (bracket :978), `AdventureEventController.finalizeEvent` :29 | |
| Duel-start perks | `DuelScene.enter()` :820. Add a level `EffectData` to `playerEffects` before :957, gated like equipment | That covers life, start cards and mana shards with no engine change. Mulligans are engine-level and off limits. |
| HUD | J`stage/GameHUD.java`. The mod builds actors in code instead of forking `hud.json` (`ResourceDisplayActor` :252, the Info button :270) | A level badge, XP bar and "Boon ready" button fit as code-built actors, so landscape, portrait and Android all work. |
| Full status page | J`scene/WorldStandingsScene.java` (mod-owned; P`ui/world_standings*.json`) | The lowest-risk home for a "Power" page. |
| Notifications | `GameHUD.addNotification` :1399 / :1412 | |
| Tunables | J`data/TuningData.java` (mod) &lt;- P`config tables/settings.json`; on/off switches in `ConfigData` (stock) | Example: `pillageKills` (round 484). |

### 1.4 Constraints

- **Save compatibility:** new player fields with `containsKey` guards. **Existing saves** need a starting level (see open
  question 11): a 150-win save showing "Ascendance 1" would feel wrong.
- **Data-driven:** the XP values, curve, cap steps, Boon pool and Spoil rarities all go in one new
  P`config tables/ascendance.json`. Code holds only the rules.
- **Upstream merges:** the touch points in stock files are one-line calls into a mod-added `util/Ascendance` (the
  round-484 pattern). Those files are DuelScene, AdventurePlayer, GameHUD, AdventureQuestController, WorldStage, ArenaScene,
  AdventureEventController, PlayerStatisticScene and ConfigData. DuelScene, WorldStage and AdventurePlayer already carry
  large mod diffs; this adds roughly 10-20 lines to each.

---

## 2. Phase 2 - how comparable games do it

| Game | Progression | Level rewards | Anti-grind / difficulty | What we would borrow |
|---|---|---|---|---|
| **Shandalar** (1997) | No XP. Life = 10 + mana links (from quests; lost when a wizard takes the city) | Cards; World Magics (overworld-only powers) | The wizard clock; deck minimum by difficulty | Small life gains you earn; **overworld-only perks** |
| **MTG Arena** | Seasonal mastery track, 1,000 XP per level | Packs, orbs, cosmetics | Daily and weekly caps | Bonuses for first wins |
| **Forge Adventure** (stock) | None | Gold, shards, items, cards; +1 life per boss | Difficulty sets life and prices | Our baseline: a level would be the first new power channel |
| **LoR Path of Champions** | Champion level 30 + account-wide Legend Level 30 | Level = **more slots and options** (items, relics, rerolls), not stats | Legend XP only from **first clears**; XP stops at the cap | Levels as options; first-clear XP |
| **Slay the Spire** | No XP; score unlocks per character | Unlocks widen the reward pool | Ascension 1-20 (opt-in handicaps); score weighted to elites and bosses | Boss- and elite-weighted XP |
| **Monster Train** | Clan levels 1-10 | Cards and champions unlocked | Covenant ladder | XP split by colors played (Option C) |
| **Inscryption** | No XP; risky card upgrades | Stats, fusion | Push-your-luck | (not adopted) |
| **Hearthstone** Dungeon Run / Duels / Mercenaries | Treasures by win count; Mercenaries level to 30 | **Pick 1 of 3** bundles and treasures; Mercenaries add flat stats | Escalating pools | Pick 1 of 3; **not** flat creature stats |
| **Thronebreaker** | Resources + camp upgrades | Deck capacity, unit upgrades | Progression **ran out by mid-game** | Pace the curve to the endgame |
| **Yu-Gi-Oh! Dark Duel Stories** | Deck-cost limit + duelist level | Room for stronger cards | Losses never lower it | (Considered and rejected: it would lock out cards players own) |
| **HoMM III** | XP curve; level = +1 stat and **pick 1 of 2 secondary skills** | 8 skill slots x 3 ranks | Finite map, steep curve | **The Boon structure**: offers, ranks, a guaranteed upgrade offer |
| **Marvel Snap** | Collection Level | Cards from **ordered pools** | Pools gate variety, not stats | Spoils' rarity by level |
| **Card Hunter** | XP opens slots and item tiers | Wider loadout | Tier tokens | Levels open options |
| **Griftlands** | Card XP | Card upgrades | XP capped after turn 6 (players farmed long fights) | Never pay XP for turns or damage |

**Lessons that shape the recommendation:**

1. **Levels should add options, not height.** In Magic, flat stats mean +life or +X/+X, and they trivialize matchups.
2. **XP should mean a meaningful win:** weight it by enemy rank, pay extra for first clears, bosses and elites, and pay
   little for enemies you have outgrown (Pokemon Gen V, the FF XI "too weak" rule).
3. **Make level-ups choices** (HoMM, Dungeon Run).
4. **Never scale enemies to the player's level** (Final Fantasy VIII).
5. **Pace against the content** (Thronebreaker ran dry mid-game).
6. **Block farming at the source:** no XP for turns or damage, and no repeatable cheap sources.

The table you sent ("weakest only" takes 3x the time) makes the same anti-grind point. Your Java example's soft-capped
**kills-per-level** pacing is what the curve below is built on.

---

## 3. Three design options

### Option A - "Renown" (light): a visible level, fixed rewards

- **What it is:** XP from wins (scaled by rank, with the outgrown rule) plus quests and firsts.
- **Rewards:** each level pays a fixed reward on a schedule (shards, a card pack, a title every 5 levels). No choices
  and no duel perks.
- **Rank:** could optionally replace the hidden rank with the visible level.
- **For:** smallest change (about 2 rounds), zero duel-balance risk, makes progress visible.
- **Against:** the rewards duplicate gold and shards (the economy is already rich), there is no build identity, and it
  gets dull by level 10. It is a progress bar, not a system.

### Option B - "Ascendance" (recommended): levels, Boon choices, Seal-bound cap

- **What it is:** Option A's XP, plus a **Boon** (pick 1 of 3) on even levels and a **Spoil** (pick 1 of 3 cards) on odd
  levels.
- **Boons:** 18 Boons with 1-3 ranks each. They are mostly overworld, economy, command and resilience perks. Only three
  touch the duel, and those are capped.
- **The cap** is bound by the Seals the main quest already has.
- **For:**
  - It makes the main quest's premise ("take back what was forged from your stolen power") into the leveling.
  - Choices give each run a build (HoMM's lesson).
  - Duel power stays in the deck.
  - It fills gaps nothing covers today: guards that never grow, travel, defeat costs.
- **Against:** about 5-6 rounds of work; 18 Boon effects touch about 15 code sites; more balance tuning.

### Option C - "Attunement": five color tracks

- **What it is:** XP goes to the colors of the deck you won with (Monster Train / LoR champions), and each color levels
  to 10 for color-themed perks and cards.
- **For:** the most "Magic" flavored option; rewards playing a color.
- **Against:**
  - It splits progress, so switching decks feels punished.
  - It overlaps confusingly with **color reputation** (your standing with the five AI colors) - two five-color meters with
    different meanings.
  - It needs five times the content, about 8 rounds.
- Better as a later layer on top of B, if at all.

**Why B over A:** A's rewards compete with an economy that already pays plenty. B's Boons pay in things money can't buy
(guards that grow, softer defeats, faster travel, better deals), and the choice is the fun. **Why B over C:** one meter,
no deck-switching penalty, no collision with color reputation.

---

## 4. The recommended option in full - "Ascendance"

### 4.1 Theme and naming

- **The fiction:** the Five broke your realm and forged your stolen power into five Seals (quest 52). Every victory
  pulls some of that power back.
- **Names:**
  - XP is **Power** ("+28 Power").
  - The level is **Ascendance** ("Ascendance 7").
  - Level rewards are **Boons** (gifts of your returning power) and **Spoils** (the realm's tribute: a card).
- **The cap:** your power is **bound**. You reach Ascendance 10 on your own, and each Seal you take back adds 4.
  **XP keeps banking while bound**, so breaking a Seal gives a surge: several levels at once.
- **Titles every 5 levels:** Unbound (5), Reclaimer (10), Warden of Ash (15), Seal-breaker (20), Sovereign (25),
  Ascendant (30). Shown on the HUD tooltip, the Power page and the statistics screen.
- Alternative names if you prefer: Renown, Sovereignty, Spark.

### 4.2 XP sources (all values in `ascendance.json`)

**Duel wins** (world, town, dungeon, arena round), by the enemy's rank:

| Rank | Base Power |
|---|---|
| Apprentice | 10 |
| Adept | 18 |
| Master | 28 |
| Archmage | 40 |

**Multipliers.** The highest one applies; they do not stack.

| Situation | Multiplier |
|---|---|
| Boss | x3 |
| Legend | x2.5 |
| First win vs this enemy (already tracked by the win/loss record) | x2 |
| Town assault, castle gate, treasure guardian | x2 |
| Cave or war champion, territory mage | x1.5 |

**Outgrown rule (anti-grind):**
- Each rank has a band top: Apprentice 8, Adept 15, Master 22, Archmage none.
- For every Ascendance level above the band top, a win pays 10% less, down to a floor of 20%.

| An Apprentice win at Ascendance | 8 | 10 | 12 | 14 | 16+ |
|---|---|---|---|---|---|
| Pays | 100% | 80% | 60% | 40% | 20% |

Grinding Apprentices at level 16 takes five times as long as fighting enemies at your level. That is the same lesson as
your table's "weakest only" column, pushed harder.

**No XP from:**
- losses (open question 3)
- guard fights (the guard fought, not you)
- Deck Tester
- the Coin Challenge
- spectated fights
- turns, damage or anything inside a duel (the Griftlands lesson)

**Not a duel:**

| Source | Power | Limit |
|---|---|---|
| First entry to a place | town or landmark 5, dungeon or cave 3 | once per place |
| Clear a place | cave 8, dungeon 15, castle or lair 30 | once per incarnation (`DungeonSources.onCleared`) |
| Restore a town / capture a town / raise the Capitol | 30 / 40 / 100 | once each |
| Side quest completed | 20 | |
| Invasion repelled | 50 / 75 / 100 / 150 by leader rank | |
| Pillage stopped | 25 | |
| Arena bracket won | 40 | once per venue per week, as now |
| Inn tournament | 1st 60, 2nd 30, 3rd-4th 15 | |
| Treasure dug up | 75 | 6 per run |
| Main-quest step | 50 | |
| **Seal reclaimed** (a castle lord beaten) | **200, and +4 cap** | 5 per run |

**Budget check over one run:**
- Duels: about 320 wins x about 25 Power ≈ 8,000.
- Everything else: about 5,200 (about 40%).
- Total: about 13,000, against 10,900 to reach 30. A completed run reaches the cap with a small margin.

### 4.3 The curve

- **Formula:** XP for the next level = at-level wins needed x expected Power of one at-level win (10 + 0.85 x level)
  x 1.4 (the non-duel share), rounded to 5.
- **Wins needed per level** follow your example's shape: 2 at level 1, rising by 1.1 per level, soft-capped at 13.
- **The result:** each level takes about the same time from level 12 on (about 13 wins, 1.7 hours), and the XP numbers
  keep climbing because stronger enemies pay more.

| Level | XP to next | Total XP to reach next | Wins for this level | Total wins | Hours (8 min/duel) |
|---|---|---|---|---|---|
| 1 -> 2 | 30 | 30 | 2.0 | 2 | 0.3 |
| 2 -> 3 | 50 | 80 | 3.1 | 5 | 0.7 |
| 3 -> 4 | 75 | 155 | 4.3 | 9 | 1.2 |
| 4 -> 5 | 100 | 255 | 5.3 | 15 | 2.0 |
| 5 -> 6 | 130 | 385 | 6.5 | 21 | 2.8 |
| 6 -> 7 | 160 | 545 | 7.6 | 29 | 3.8 |
| 7 -> 8 | 190 | 735 | 8.5 | 37 | 5.0 |
| 8 -> 9 | 230 | 965 | 9.8 | 47 | 6.3 |
| 9 -> 10 | 265 | 1230 | 10.7 | 58 | 7.7 |
| 10 -> 11 | 310 | 1540 | 12.0 | 70 | 9.3 |
| 11 -> 12 | 350 | 1890 | 12.9 | 83 | 11.0 |
| 12 -> 13 | 370 | 2260 | 13.1 | 96 | 12.8 |
| 13 -> 14 | 385 | 2645 | 13.1 | 109 | 14.5 |
| 14 -> 15 | 400 | 3045 | 13.0 | 122 | 16.2 |
| 15 -> 16 | 415 | 3460 | 13.0 | 135 | 18.0 |
| 16 -> 17 | 430 | 3890 | 13.0 | 148 | 19.7 |
| 17 -> 18 | 445 | 4335 | 13.0 | 161 | 21.4 |
| 18 -> 19 | 460 | 4795 | 13.0 | 174 | 23.2 |
| 19 -> 20 | 475 | 5270 | 13.0 | 187 | 24.9 |
| 20 -> 21 | 490 | 5760 | 13.0 | 200 | 26.6 |
| 21 -> 22 | 505 | 6265 | 13.0 | 213 | 28.4 |
| 22 -> 23 | 520 | 6785 | 12.9 | 226 | 30.1 |
| 23 -> 24 | 540 | 7325 | 13.1 | 239 | 31.8 |
| 24 -> 25 | 555 | 7880 | 13.0 | 252 | 33.6 |
| 25 -> 26 | 570 | 8450 | 13.0 | 265 | 35.3 |
| 26 -> 27 | 585 | 9035 | 13.0 | 278 | 37.0 |
| 27 -> 28 | 600 | 9635 | 13.0 | 291 | 38.8 |
| 28 -> 29 | 615 | 10250 | 13.0 | 304 | 40.5 |
| 29 -> 30 | 630 | 10880 | 13.0 | 317 | 42.2 |

**How the curve relates to today's hidden rank:**
- Level 6 comes at about 21 wins, level 11 at about 70 and level 18 at about 160. Today's rank steps at 20, 60 and 150
  wins - nearly the same places.
- If you ever want the visible level to drive the world rank (open question 7), the thresholds would barely move.

**Assumptions to verify with an agent soak** (the bridge can play 150+ duels unattended): 8 minutes per duel including
travel, about 320 wins in a full run, and the week brackets' rank mix.

**Past the cap** (your example's "infinite" idea, without extra power): every 600 Power past 30 is an **Echo** that pays
25 shards. Nothing else.

### 4.4 What each level gives

| Level | Reward |
|---|---|
| 2, 4, 6 ... 30 (15 times) | **Boon** - pick 1 of 3 (4.5) |
| 3, 5, 7 ... 29 (14 times) | **Spoil** - pick 1 of 3 cards in your deck's colors. Uncommon at 3-9, Rare at 11-19, Rare or Mythic at 21-29. Or take 30 x level gold instead |
| 5 | Title *Unbound*; Boon offers can be re-rolled once per level-up (10 shards) |
| 10 | Title *Reclaimer*; Boon offers show **4** choices |
| 15 | Title *Warden of Ash*; **+1 max life** (the one free life step) |
| 20 | Title *Seal-breaker*; your roaming guards' **rank can be raised one step for free** once |
| 25 | Title *Sovereign*; Spoils show 4 choices |
| 30 | Title *Ascendant*; a cosmetic crown on the HUD badge; Echoes begin |
| 10, 14, 18, 22, 26 | **Bound** until the next Seal; XP banks |

### 4.5 The Boon pool (18 Boons, 40 ranks; a full run picks 15)

Offer rules (HoMM III):
- 3 offers (4 from level 10).
- At least one offer **upgrades a Boon you already have**, and at least one is **new**.
- At most **one duel Boon** per offer.
- A pending choice waits until you take it. You choose on the map or in a town, never mid-duel.

| Group | Boon | Ranks | Effect per rank | Where it hooks |
|---|---|---|---|---|
| **Duel (capped)** | Vigor | 3 | +1 max life | `AdventurePlayer.addMaxLife` |
| | Shardwell | 2 | +1 mana shard at the start of each duel | level `EffectData.extraManaShards` in `playerEffects` |
| | Insight | 1 | Scry 1 at the start of each duel | a custom command-zone card (like the boss effect cards) via `startBattleWithCardInCommandZone` |
| **Economy** | Haggler | 3 | -5% shop prices | the price multiplier next to the reputation one (`ColorReputation`) |
| | Fence | 2 | +10% sell value | the sell path in `RewardScene` / `AdventurePlayer` |
| | Prospector | 3 | +15% from resource pickups and mines | `ResourceSpawns`, `EconomyBuildings` |
| | Spoilsman | 2 | +1 card on a first win against an enemy | `CardBudget` (first-win branch) |
| **Travel** | Pathfinder | 3 | +5% overworld speed | level `EffectData.moveSpeed` |
| | Far Sight | 2 | +15% vision radius | level `EffectData.visionRadiusMultiplier` |
| | Wayfarer | 2 | +2 bonfire uses; treasure digs cost 2 shards less | bonfire item, `TreasureHunt` |
| **Diplomacy** | Envoy | 3 | -15% color reputation lost from wins | `ColorReputation` win accounting |
| | Beloved | 2 | I: restores give +1 town reputation; II: invasions and pillages too | `TownRestoration`, `InvasionQuests`, `TownPillage` |
| **Command** | Marshal | 3 | Roaming guards +2 starting life | `RoamingGuards.lifeFor` |
| | Quartermaster | 2 | -15% guard wages (local and roaming) | `RoamingGuards` / `EconomyBuildings` wages |
| | Architect | 2 | -10% building and restore costs | `EconomyBuildings`, `TownRestoration` fees |
| **Resilience** | Stubborn | 2 | -25% of the life and gold a defeat costs | `AdventurePlayer.defeated()` |
| | Mender | 2 | 25% chance an equipped item does not crack on a defeat; -25% repair cost | `Current.generateDefeatMessage`, `InventoryScene` repair |
| | Pathwarden | 1 | Pillage and invasion deadlines +2 days | `TownPillage`, `InvasionQuests` |

**Deliberately left out:**
- Anything that softens **notoriety** or "enemies learn your tricks". Those are your difficulty systems, and a Boon
  would just switch them off.
- Hand size and mulligans (strong, and engine-level).
- +X/+X on creatures.
- Any change to ante.

**Duel power ceiling:** +3 life, +2 mana shards and scry 1. On Insane, Vigor III is +60% of the starting 5 life.
Open question 6 asks whether Vigor should be a percentage instead.

### 4.6 Respec, defeat, New Game+, difficulty

- **Respec:** the **Rite of Unbinding** at the Capitol costs 100 shards. It refunds every Boon pick, and you re-pick from
  fresh offers.
- **Defeat:** no XP is lost. Defeats already cost life, gold and items (lesson: don't punish twice).
- **New Game+:** Ascendance resets to 1 and the Boons are forgotten, like the rank. Titles are kept as a record. A
  possible "keep Ascendance" box in the existing New Game+ carry dialog is open question 5.
- **Difficulty:** the same XP and Boons on every difficulty. Difficulty already scales rewards and enemy life.
- **World difficulty is unchanged:** the week brackets and the win-count rank stay as they are.

### 4.7 What it replaces, complements, and overlaps

- **Replaces nothing.** No existing channel becomes redundant. The level fills real gaps: no reward for overall progress,
  guards that never improve, travel, and the cost of defeats.
- **Avoids overlap by design:**
  - Boons never touch reputation thresholds, research or blueprint gates, or the difficulty ladders.
  - Spoils are small next to duel loot (about 14 cards per run, against hundreds).
- **Complements:**
  - the first-win bonus (the same "bestiary" spirit, now also paid in Power)
  - max life (Vigor and level 15 add 4 on top of the +Life rewards)
  - the main quest (Seals = cap)
- **Optional later:** show the hidden rank on the Power page as "Threat: the world sends Masters now", so the two axes
  read side by side.

### 4.8 UI touchpoints

- **HUD** (code-built, all layouts):
  - an "Asc 7" badge on the avatar
  - a thin Power bar under the resource display
  - a pulsing **Boon** button while a choice is pending (like the Info button)
  - a toast on level-up and after each duel ("+28 Power")
- **The choice dialog:** 3-4 Boon cards with name, rank and effect, sized for a 270px-tall screen. Spoils use the
  existing card-reward picker.
- **The Power page** in World Standings (mod-owned), showing:
  - level, XP and the next level's cost
  - "Bound at 14 - 2 Seals of 5"
  - chosen Boons with their ranks
  - titles
  - this run's Power by source
- **Statistics screen:** one line, "Ascendance 12 - Reclaimer".
- **Agent observer and cheats:** `asc give <n>`, `asc set <level>`, `asc boon <name>`, plus the state fields.
- **Log:** `[TFR-Ascend]` lines for every award (source, base, multiplier, outgrown factor), level-up, offer and pick.

### 4.9 Files and classes

| File | Kind | Change |
|---|---|---|
| `util/Ascendance.java` | **new** | Power awards, curve, cap and Seals, level-up queue, offers, Boon queries (`rank(name)`), Echoes, cheats' backend, `[TFR-Ascend]` log |
| `util/AscendanceUI.java` | **new** | Boon dialog, Spoil picker, Rite of Unbinding, the Power page section |
| `data/AscendanceData.java` | **new** (json-loaded, never saved) | XP table, multipliers, bands, curve parameters, cap steps, Boon pool, Spoil rarities |
| P`config tables/ascendance.json` | **new** | All the numbers above |
| `player/AdventurePlayer.java` | stock | Fields `ascPower` (int), `ascBoons` ("name:rank" list), `ascPending`, `ascOffers`, `ascTitles`, `ascSeals`; `save`/`load` with `containsKey`; `clear`; New Game+ reset |
| `scene/DuelScene.java` | stock | `afterGameEnd`: `Ascendance.onDuelResult(...)`. `enter`: add the level `EffectData` to `playerEffects` |
| `util/AdventureQuestController.java` | stock | Quest completed -> award |
| `stage/WorldStage.java` | stock | First entry (before `visit()`), a getter for the fight flags, Seal hook |
| `scene/ArenaScene.java`, `util/AdventureEventController.java` | stock | Bracket and Inn awards |
| `stage/GameHUD.java` | stock | Badge, bar, Boon button (code-built) |
| `scene/PlayerStatisticScene.java` | stock | One line |
| `data/ConfigData.java`, P`config.json` | stock / data | `ascendanceEnabled` |
| `DungeonSources`, `TownRestoration`, `TreasureHunt`, `TownPillage`, `InvasionQuests`, `RoamingGuards`, `EconomyBuildings`, `ColorReputation`, `CardBudget`, `ResourceSpawns`, `WorldStandingsScene` | mod | One-line awards and the Boon effects |
| `stage/ConsoleCommandInterpreter.java`, `agent/AgentObserver.java` | stock / mod | Cheats and state |
| Docs | | GAME_GUIDE section, MOD_CHANGELOG, CORE_ENGINE_CHANGES |

**Estimate - about 5-6 rounds, each agent-tested:**
1. Power model, curve, saving, every XP hook, HUD badge and bar, toasts, cheats, logs, and the existing-save backfill.
2. The Boon and Spoil framework: offers, the pending button, dialogs, titles, the Power page.
3. and 4. The 18 Boon effects, about 15 sites, each tested.
5. Seal-bound cap, New Game+, Rite of Unbinding, then a balance soak (the agent plays 150+ duels; compare against the
   curve table and retune `ascendance.json`).
6. (if needed) Android and portrait pass and tuning.

---

## 5. What I would not do (and why)

- **Scale enemies to the level** (Final Fantasy VIII). TFR already scales the world by calendar and record. Adding level
  scaling would make leveling feel like a trap.
- **A deck-budget level** (Yu-Gi-Oh! Dark Duel Stories). It would lock out cards players already own. In a game where
  "power comes from the deck", that cuts against the core.
- **Hand-size or mulligan perks.** These are the strongest levers in Magic. They are engine-level and would trivialize
  Insane.
- **XP for anything inside a duel** (turns, damage, creatures killed). That invites stalling (Griftlands).

---

## 6. Open questions for you

1. **Direction:** A (Renown, light), **B (Ascendance, recommended)**, or C (color Attunement)?
2. **Seal-bound cap:** yes (recommended, base 10 + 4 per Seal), Seals plus Ring Cities (finer steps), or no cap gate?
3. **XP on a loss:** none (recommended; defeats already cost plenty) or a 25% "lesson"?
4. **Difficulty:** the same XP everywhere (recommended), or Hard x1.1 / Insane x1.25?
5. **New Game+:** reset (recommended, titles kept) or a "keep Ascendance" box in the New Game+ carry dialog?
6. **Duel Boons:** keep Vigor, Shardwell and Insight capped as listed, make Vigor a percentage of starting life so Insane
   isn't +60%, or go adventure-only?
7. **World rank:** keep the hidden win-count rank separate (recommended), or let Ascendance drive it (the thresholds would
   be about 6 / 11 / 18)?
8. **Spoils:** card picks (recommended) or plain gold or shards?
9. **Rite of Unbinding:** 100 shards at the Capitol - right price and place?
10. **The per-duel "+28 Power":** a HUD toast (recommended) or a line on the loot screen?
11. **Existing saves:** on first load, estimate Power from the save (wins by rank, visited places, restored towns,
    quests), so a long save starts around its true level with its Boon picks waiting one at a time (recommended), or
    start everyone at 1?
12. **Names:** Power / Ascendance / Boons / Spoils, and the six titles - keep, or would you rather Renown or Spark?

---

## 7. Decisions (2026-10-08) and the revised plan

The user: "Let's try out option B. I do want to add one thing though. Equipment slots should also be gated behind
leveling. You start with 1 slot. (Not utility, just the 5 main slots: Neck, Chest, Left Hand, Right Hand and Boots.)
At level 5, you get the second and at level 10 you get the 3rd, level 15, the 4th and level 20 the 5th. They are all
available, but you can only equip so at level 5, you can have any 2 of the 5 equipped. +1 Health is also very strong,
so maybe we give that at level 6, 11, 16, 21, 26 and 30? ... I'm thinking with each level you get something like a
choice of 3 from the following list: +3 life for the first duel each day (the second time you take this it would be
the first 2 duels, etc.), + xxx Gold, + xxx Shards, + xxx Wood, + xxx Stone, + random rare card(s), + random common
item, + ????" and: "Let's say it won't work on existing saves, have to do a NG+ or new game. Let's not have a
Seal-bound cap for now."

### 7.1 What stays from Option B

- Power (XP) and Ascendance 1-30.
- The XP sources, multipliers and outgrown rule (4.2).
- The curve (4.3), with no Seal-bound cap.
- The HUD badge, bar and toasts, the Power page, cheats and logs (4.8).
- **Dropped:** the Seal-bound cap, the Boon and Spoil alternation (4.4-4.5), the Rite of Unbinding, and estimating a
  level for existing saves.

### 7.2 Which saves get it

- **New Game and New Game+ only.** A save started either way carries an `ascendance` flag.
- **Older saves never get the system:** no XP and no slot limit, so nothing they have equipped changes.
- **New Game+ starts over at Ascendance 1.**

### 7.3 Fixed level rewards

| Level | Reward | Reached at about |
|---|---|---|
| 1 | Wear **1** item in the main slots | start |
| 5 | **2** main items | 15 wins (2 h) |
| 6 | **+1 max life** | 21 wins |
| 10 | **3** main items | 58 wins (8 h) |
| 11 | +1 max life | 70 wins |
| 15 | **4** main items | 122 wins (16 h) |
| 16 | +1 max life | 135 wins |
| 20 | **5** main items (all) | 187 wins (25 h) |
| 21, 26, 30 | +1 max life each | 200 / 265 / 317 wins |

**The main slots** are Neck, Body, Left, Right and Boots. Every slot stays open: the limit counts how many items you
wear across them. Utility slots are never limited: Ability 1-3, Medal, Blessing, Heart, Token and Pocket.

### 7.4 The choice levels

There are 19 of them: 2, 3, 4, 7, 8, 9, 12, 13, 14, 17, 18, 19, 22, 23, 24, 25, 27, 28 and 29. At each one you pick
**1 of 3** offers.

**Offer rules:**
- The 3 offers are always different.
- Each offer holds at least one **lasting** option and at least one **one-time** reward, while any are left.
- A lasting option stops appearing once it reaches its maximum picks.
- A one-time reward with nothing to give (every blueprint owned, every map piece found) is never offered.
- A pending choice waits for you. You pick on the map or in a town, never mid-duel.

### 7.5 The choice pool (proposed; amounts scale with the level L, all in `ascendance.json`)

**One-time rewards:**

| Option | Gives | Example L4 / L14 / L28 |
|---|---|---|
| Gold | 40 x L | 160 / 560 / 1,120 |
| Shards | 4 x L | 16 / 56 / 112 |
| Wood | 12 x L | 48 / 168 / 336 |
| Stone | 12 x L | 48 / 168 / 336 |
| Rare cards | Random, in your deck's colors: 1 rare (L2-12), 2 rares (L13-22), 1 rare + 1 mythic (L23+) | |
| Item | A random item, Common (L2-12), Uncommon (L13-22), Rare (L23+) | |
| Booster pack | One pack of an edition you have unlocked | |
| Map fragment | One piece of a treasure map you are still missing | |
| Blueprint | A random shop blueprint you don't own, from the tiers your reputation allows | |
| Bronze Coins | +2 coins | |
| Goodwill | +2 reputation in the town you hold with the least | |
| Mend | Every cracked item repaired, and every downed roaming guard back on its feet | |

**Lasting options (stack up to the maximum):**

| Option | Each pick | Max |
|---|---|---|
| **Morning Vigor** (the user's) | +3 life in the first duel of each day; each pick covers one more duel | 3 |
| Haggler | -5% shop prices | 3 |
| Swift Feet | +5% overworld speed | 3 |
| Prospector | +15% from resource pickups and mines | 3 |
| Far Sight | +15% vision radius | 2 |
| Marshal | Roaming guards +2 life | 3 |
| Stubborn | Defeats cost 25% less life and gold | 2 |
| Mender | 25% chance an equipped item doesn't crack on a defeat | 2 |
| Spoilsman | +1 card on your first win against each enemy | 2 |
| Shardwell | +1 mana shard at the start of each duel | 2 |
| Envoy | -15% color reputation lost from wins | 2 |

**Kept out, as before:** anything that softens notoriety or "enemies learn your tricks", hand size, mulligans, ante.

### 7.6 How the slot limit works

- **Equipping:** equipping a main-slot item while at the limit is refused, with a clear message: "Your power allows 2
  main items - Ascendance 10 brings a third." Swapping the item in a slot you already use is always allowed.
- **The Inventory and Armory** show "Main items 2 / 3" by the doll.
- **Starting kits:** Easy starts with two main-slot items (Manasight Amulet and Leather Boots). At level 1 the boots
  are worn and the amulet waits in the bag.
- **Deck loadouts** (each deck remembers its gear): switching to a loadout with more main items than allowed keeps the
  first ones and leaves the rest in the bag, with a notification.
- **New Game+ with items kept:** you go back to 1 worn main item.
- **Roaming guards** wear what they like; the limit is the player's.
- **Gauntlets** grant extra hand slots (Left2 and Right2). Whether those count toward the limit is question 1 below.

### 7.7 Timing to watch

With the curve in 4.3, the full kit of 5 arrives at about 187 wins (about 25 hours). Today players wear 5 main items as
soon as they find them, so the first half of a run gets noticeably harder, Insane most of all. If that feels too slow
in play, I would speed up the curve's early levels rather than move the slot levels.

### 7.8 Revised rounds

1. **Core:** the save flag; Power, the curve and every XP hook; level-ups with the +1 life levels; the HUD badge and
   bar; toasts; cheats; `[TFR-Ascend]` logs.
2. **The slot limit:** equip paths (Inventory, Armory, deck loadouts, starting kit, New Game+), the "Main items" UI.
3. **The choice framework:** offers, the pending button, the pick dialog, the one-time rewards.
4. **The lasting options:** 11 effects at about 10 code sites.
5. **The Power page, then an agent soak** (150+ duels) and tuning.

### 7.9 Open questions now

1. Do the gauntlets' extra hand slots (Left2 / Right2) count toward the main-item limit? (Recommended: yes - otherwise
   a gauntlet sidesteps it.)
2. The pool in 7.5: drop or add anything? Are the amounts right?
3. Titles every 5 levels: keep them as free flavor, or drop them?

### 7.10 Decisions, second pass (2026-10-08)

The user: "For the Gauntlets, no. it's a special item. so it does give a bonus item slot. ... Let's say you choose
Haggler. -5% shop prices, then the next time it would be -10% and the 3rd -15%. So it's like a mini decision, if you want
to invest heavily into one direction. Let's add -x% cheaper building cost/town repair. Goodwill should be for your own
towns only. Go with the titles. Let's limit the roaming guards equipment slots also. Apprentice - 2, Adept - 3, Master -
4 and Archmage 5. Let's actually combine +1 life and Equipment slot unlock at 5, 10, etc. This will give us more
opportunity for the other 3 random picks."

- **Gauntlets:** their extra hand slots (Left2, Right2) are a bonus and never count toward the limit.
- **Lasting options stack as an investment:** Haggler is -5%, then -10%, then -15%. Every lasting option works this way.
- **Architect** is added to the lasting options: -10% building and town-restore costs per pick, max 3.
- **Goodwill** is for the player's own towns only.
- **Titles stay:** Unbound (5), Reclaimer (10), Warden of Ash (15), Seal-breaker (20), Sovereign (25), Ascendant (30).
- **Roaming guards' main items, by rank:** Apprentice 2, Adept 3, Master 4, Archmage 5. Lowering a guard's rank sends
  the extra items back to the Armory storage. This applies to saves with Ascendance, like the player's limit.
- **Milestone levels combine** +1 max life, the slot unlock and the title:

| Level | Reward |
|---|---|
| 5 | +1 max life, 2 main items, *Unbound* |
| 10 | +1 max life, 3 main items, *Reclaimer* |
| 15 | +1 max life, 4 main items, *Warden of Ash* |
| 20 | +1 max life, 5 main items, *Seal-breaker* |
| 25 | +1 max life, *Sovereign* |
| 30 | +1 max life, *Ascendant* |
| every other level (23 of them) | pick 1 of 3 |

### 7.11 Decisions, third pass (2026-10-08) - built in round 493

The user: "I do think the XP/Leveling system is going to be controversial, so maybe add an option is settings to turn it
all off. Also, in the config, add options to control the speed of leveling and how much life is given at the milestone
levels. Maybe we don't cap it at 30, but make the leveling MUCH slower after 30 and give +1 life for each level?"

- **Settings switch**: "Leveling (Ascendance)", on by default. Off turns off all of it: no Power, no main-item or guard
  limits, no HUD panel. What was already gained stays, and switching it back on carries on (items over the limit are
  taken off).
- **Config**: `levelingSpeed` multiplies every award; `milestoneLife` is the life given at each milestone level.
- **No cap**: past 30, a level costs 2,000 Power, 250 more for each level after (about 40 wins per level at that stage,
  against 13 before). Each gives +1 max life (`postCapLife`) and nothing else.
