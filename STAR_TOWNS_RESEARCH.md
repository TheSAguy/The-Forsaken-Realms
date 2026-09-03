# Star Towns & AI Starting Cards — Research Notes

*Research round, 2026-08-31. Nothing was implemented. Every claim below was read in the source and
independently re-verified by a second pass; citations are `file:line` against the repo at commit
`7fd6eeed416`. Where a number was measured rather than reasoned about, that is stated.*

Two user ideas drove this:

> **(1)** "Currently, something that's missing is a way for the AI to Win, besides taking your
> Capitol. I want to create a map, where there are 5 towns in the middle of the map, around the
> camp fire. It will be in a star shape, that kind points to the 5 AI capitols. The idea is that
> the AI will try and take those and if they do, they win."
>
> **(2)** "I want the player to be able to attack an AI controlled town. But when that battle
> starts, the AI should start with card(s)... I don't think there is a 1 vs 2 opponents at all in
> Forge Adventure, correct?"

Follow-up: *"The 5 towns in the middle will have their own layout / icons."*

---

## Headline findings

1. **1-vs-2 already exists and already ships in this game.** `EnemyData.nextEnemy` builds a real
   simultaneous multiplayer match, up to 1-vs-8. "Goblin Pack" uses it today. The ceiling is not
   the engine or the Adventure layer — it is the **art pipeline**, and it is a hard crash.
2. **The world is already a pentagon.** The five color biomes are laid out around the map centre,
   and the five `<Noun> Capital` POIs sit at mathematically exact, seed-independent positions. The
   star works *with* the existing layout.
3. **`radiusFactor: 0` is a working, zero-code exact-placement mechanism** — it is how the five
   castles are positioned today. Measured safe radius for the star: **45 tiles** (100% of 300 seeds).
4. **There are exactly two game-over paths**, and both funnel through one method. Adding a third is
   a small, well-defined change.
5. **The player cannot attack an AI town today** — no siege mechanic exists in code or data.
6. **`startBattleWithCard` already applies to the AI side**, and in a chained fight it applies to
   *every* AI seat.

---

## Part 1 — The Star of Five Towns

### 1.1 The map, in numbers

`world/world.json`: 700 × 700 tiles, 16 px each (11200 × 11200 px). Player start `0.5, 0.5` →
tile **(350, 350)**.

The **camp fire is the Spawn POI** ("Secluded Encampment", `main_story/spawn.tmx`). It has no
`radiusFactor` or offsets, so it sits at exactly tile (350, 350). `World.java:1773-1788`
special-cases its minimap art and calls it "its own already-distinct overworld campfire sprite" —
this is the landmark the user means.

Biome centres (`biomes/*.json`) are already a pentagon:

| Biome | startPoint X, Y |
|---|---|
| white | 0.50, 0.22 |
| blue | 0.79, 0.43 |
| black | 0.70, 0.78 |
| red | 0.31, 0.78 |
| green | 0.22, 0.43 |

### 1.2 Where the AI capitals actually are — three different things

This matters, because the star must "point at them."

- **The `<Noun> Capital` POI** — fixed and exact. No `radiusFactor`/offset, so it lands on its
  biome centre every seed. **But** Territory Control always sweeps these to neutral; they are not
  where the in-game capital ends up.
- **The five Chapter-1 castles** — what the player actually sees, but seed-jittered.
- **The effective post-sweep capital** — runtime only.

Bearings from the three anchors differ by up to **7.8°** before jitter — about 7 tiles of lateral
offset at a 45-tile radius. **This is an open design question (see 1.7).**

### 1.3 The placement pipeline — what constrains a POI

All inside `World.generateNew()`, `World.java:827-1662`. Position is chosen by
(`World.java:1062-1072`):

```
radius = sqrt(random.nextDouble()/2 * poi.radiusFactor)
theta  = random.nextDouble() * 2*PI
x = radius*cos(theta) * (biome.width * width / 2) + biome.startPointX * width
    + poi.offsetX * (biome.width * width)
```

The **complete** list of constraints — there are only three:

| Check | Line |
|---|---|
| In bounds | `World.java:1074` |
| Biome must be the *dominant* biome at that tile | `World.java:1074` |
| No overlap with an already-placed POI's 8×8-tile box | `World.java:1082-1088` |

**There is no terrain check, no collision check, no water check, and no reachability check.**

> **Trap worth recording:** the terrain pass at `World.java:1218` runs *after* placement, so the
> `clearTerrain()` the placement loop does at `World.java:1135` is **completely overwritten**.
> World-gen POIs are not guaranteed to sit on walkable ground. Only the road pass and
> `addPointOfInterestNear`'s own `clearTerrain` (`World.java:1943`) clear collision after terrain
> is final.

### 1.4 Exact placement — what exists

**It exists as data, with no code change.** With `radiusFactor: 0` the formula collapses to a
constant. For a POI in `colorless.json` (startPoint 0.5/0.5, w/h 0.85):

```
x_tile = 350 + 595 * offsetX
y_tile = 350 + 595 * offsetY
```

so for a target `(dx, dy)` tiles from centre: `offsetX = dx / 595`, `offsetY = dy / 595`.

There is **no** exact-tile placement API in code. `addPointOfInterestNear`
(`World.java:1917-1952`) is an *annulus* sampler and cannot hit an exact tile — but it runs
post-terrain, so its `clearTerrain` genuinely works. A real `addPointOfInterestAt(data, tx, ty)`
would be ~15 lines next to it.

### 1.5 The geometry

Centre `C = (350, 350)`. For each color `k` with anchor `A_k`:

```
theta_k = atan2(A_k.y - C.y, A_k.x - C.x)
T_k     = (round(350 + R*cos(theta_k)), round(350 + R*sin(theta_k)))
```

**Recommended R = 45 tiles** (measured: 100% placement success across 300 seeds):

| Arm | Bearing | Tile | offsetX | offsetY |
|---|---|---|---|---|
| white | 90.00° | (350, 395) | +0.000000 | +0.075630 |
| blue | 13.57° | (394, 361) | +0.073519 | +0.017746 |
| black | −54.46° | (376, 313) | +0.043959 | −0.061543 |
| red | −124.16° | (325, 313) | −0.042466 | −0.062582 |
| green | 165.96° | (306, 361) | −0.073372 | +0.018343 |

Adjacent separations 50.3–55.7 tiles. R = 40 also measured 100%; **above R = 50 the biome check
starts failing.**

**Fallback when a tile is unusable:** spiral outward *along the radius first*, angle only as a last
resort — this keeps the arm pointing at its capital, which is the whole point:

```
for dr in 0, +2, -2, +4, -4, ... up to ±12
  for dtheta in 0, ±3°, ±6°
    accept first candidate in bounds, ≥5 tiles from every POI
```

**Drawing the star:** `World.buildRoad()` (`World.java:2555-2614`) makes the shape visible *and*
zeroes collision terrain along every tile it draws — a free reachability fix. Three options, ~3
lines each: five spokes (asterisk), a pentagon ring, or a true **pentagram** (`a0,a2,a4,a1,a3,a0`).

### 1.6 The biggest risk — biome ownership

A POI in `colorless.json` is rejected unless waste is *dominant* at its exact tile
(`World.java:1074`). The waste/color border is noise-driven and moves per seed, and the color
biomes reach a long way inward. This was **measured** with a throwaway harness replaying the real
biome-claim loop over 300 seeds: the **white arm is tightest**, and safe radius is comfortably
under 60. Hence R = 45.

### 1.7 Open questions — Part 1

- **Anchor:** point at the fixed `<Noun> Capital` POIs (exact, JSON-authorable, but swept neutral
  and not where the real capital ends up), or the Chapter-1 castles (what the player sees, but
  runtime-only and jittered)?
- **Radius:** 45 makes a tight bowl around the campfire (~30 s walk per arm). Further out gives the
  AI a longer march and the player more room to intercept.
- **Visibility:** revealed from turn one (a loss condition probably should be), or discovered
  normally? Revealing hands the player five free fog discs at the centre, feeding the 80 %
  auto-reveal threshold.
- **Starting ownership:** neutral (AI must come take them), or one per color pre-assigned to the
  color its arm points at? The latter reads better thematically but surrounds the player with five
  hostile towns 45 tiles from spawn.
- **Starting state:** ordinary ruined wasteland towns, always pre-restored, or eligible for the
  existing 20-town "functioning neutral" seeding? Decided purely by whether star placement runs
  before or after `TownRestoration.seedFunctioningNeutralTowns` (`World.java:1614`).
- **Win threshold:** all five, or N of five? What happens to an arm whose color the player has
  already defeated (Color Defeat sweeps that color off the map entirely)?
- **Player interaction:** can the player capture/restore a star town? Does that permanently remove
  it from the AI tally, or can it be retaken? Can the Capitol be built on one?
- **Count:** nothing forces five — the pentagon comes only from there being five color biomes.

> **Critical implementation note:** mark the star by **position on a persisted `World` field**, not
> by a per-POI flag. A per-POI flag is destroyed the instant an AI captures the town — `transformInto`
> changes `getID()` — i.e. *exactly when the win condition needs to fire*.

### 1.8 The user's "own layout / icons" requirement

Bespoke star towns mean new POI entries **and** new `.tmx` maps. Note the constraint this hits:
capture looks up `"<Noun> Town <Suffix>"` by **string surgery**, so a bespoke POI needs the naming
convention honored or the capture path breaks. Cheapest path is five ordinary Waste Towns (zero new
assets, everything downstream works today); bespoke costs six POI entries and six `.tmx` files per
variant.

---

## Part 2 — How the game ends, and where the new loss hooks in

### 2.1 Exactly two game-over paths, one exit door

**`WorldStage.triggerGameLost(String)` — `WorldStage.java:676-700`.** Freezes controls, shows a
dialog with the caller's message and a "Return to Main Menu" button.

> The save is **deliberately not deleted** — the comment at `:662-667` says so explicitly ("no
> permadeath mechanic exists in this codebase to hook into"). Nothing marks the save as lost; the
> player can Continue straight back into a dead run. **Any new loss condition inherits this.**

- **Path A — lose the forced Capitol-defense duel.** Mage reaches the Capitol
  (`TerritoryControl.java:1822-1831`) → guards fight → if they fall, mage parked in
  `pendingCapitolDefenseMage` → polled every frame from `GameStage.act()` (`:502-503`) →
  `startForcedCapitolDuel` → on loss `WorldStage.setWinner()` `:496-500` → `triggerCapitolDefeat()`.
- **Path B — no neutral towns left and player owns nothing.** `TerritoryControl.java:2013-2030`,
  the last statement of `onMageArrived()`.

**Confirmed non-endings** (checked so nobody re-checks): life reaching 0 is a respawn, not a loss;
beating all five castles is not a win; dungeon/boss/Arena losses never end the run.

### 2.2 Where the star-town check goes

`onMageArrived()`'s tail (`TerritoryControl.java:2013`) is the right home — it is already the common
tail of every path that can change town ownership, and Path B's own comment argues exactly that.
The new check reads the persisted star-position field, counts how many are AI-held, and calls
`triggerGameLost()` with a suitable message.

Warning the player as arms fall is free: the same notification system Path A already uses.

---

## Part 3 — AI starting cards, and attacking a town

### 3.1 "Strange Magic" is `EffectData`, and it already targets the AI

- The dialog text is `lblEffectDialogDescription` (`en-US.properties:3582`), rendered by
  `GameStage.effectDialog()` (`GameStage.java:177-204`).
- The field is `EffectData.startBattleWithCard` (`EffectData.java:22`), resolved by
  `startBattleWithCards()` (`:47-67`), consumed in `DuelScene.addEffects()` via
  `player.addExtraCardsOnBattlefield(startCards)` (`DuelScene.java:591`).
- **It is applied to the AI**: `DuelScene.java:686-687` collects `enemy.effect` into `oppEffects`,
  and `:796` does `addEffects(aiPlayer, oppEffects)` **inside the per-seat loop** — so in a chained
  fight every AI seat gets it.

**Three constraints an implementer must know:**

1. The enemy-effect block at `:686-690` is **nested inside** `if (eventData == null ||
   eventData.eventRules.allowsBlessings)` (`:678`). Harmless for overworld/dungeon duels, but the
   enemy effect is **silently dropped** in an Inn/Arena event whose rules disallow blessings.
2. `effect` lives on **`EnemySprite`** (`EnemySprite.java:71`), not `EnemyData`. `EnemyData` has no
   `effect` field at all, and no `enemies.json` in the repo contains one. It is a **`.tmx`-only
   channel** — it cannot be authored per catalog enemy, and must be assigned to the sprite after
   construction (`duelMage.effect = ...`).
3. Because only the **head** sprite carries an `effect`, **there is no way to give different seats
   different starting cards** through this hook.

### 3.2 The player cannot attack an AI town today

No assault/siege/conquest interaction exists. Walking into a rival town runs the ordinary POI
pipeline: War-tier entry bar → capital toll → legendary warning → load the `.tmx`. All 20 color-town
maps contain **zero enemy objects**. The word "siege" appears once in the whole adventure tree, as
an unimplemented enum value.

`MOD_SCOPE.md:4195-4199` already holds the placeholder: **#87 "More Attacking Options" — Not Started.**

The only current route into a color's territory is wholesale: clear its castle boss →
`TerritoryControl.defeatColor()` reverts every town it owns to neutral ruins → rebuild them one at
a time through the Job Board.

### 3.3 The launcher template already exists, twice

`WorldStage.startForcedCapitolDuel` (`:619-638`) and `startChestDuel` (`:646-660`) both: clone the
`EnemyData`, mutate one field, wrap in a fresh `EnemySprite`, `initDuels`, `switchScene`.
`EnemyData`'s copy constructor **deep-copies the whole chain** (`EnemyData.java:92`), so
`clone.nextEnemy = <second EnemyData>` is the entire "make this fight a 1-vs-2" change, with no
data-file edits. `startForcedCapitolDuel` sets the precedent by bumping `gamesPerMatch = 3` on a
clone (`WorldStage.java:621`).

---

## Part 4 — 1-vs-2: the answer is "already supported"

### 4.1 Where the ceiling really is

| Layer | Status | Citation |
|---|---|---|
| Forge engine | Full N-player, FFA and team | `GameAction.java:1963-1985`, `Game.java:357-364` |
| Forge AI | Multi-opponent aware, excludes allies | `AiAttackController.java:205, 811-812` |
| Match hosting | N-player | `HostedMatch.java:135-138` |
| Mobile match UI | Explicit 3- and 4-player layouts | `MatchScreen.java:191-197, 1029-1055` |
| Adventure duel setup | **N-player, already wired** | `DuelScene.java:622-850` |
| **Enemy sprite atlases** | **1 avatar frame in 491 of 493 → hard crash at seat 2** | `CharacterSprite.java:314-316` |
| Rewards / quests / stats | Head enemy only | `WorldStage.java:475/477`, `MapStage.java:1552/1553` |

`DuelScene.enter()` walks the chain at `:627-630`, builds one `RegisteredPlayer` per link at
`:742`, and calls **one** `startMatch` at `:850`. Genuine simultaneous multiplayer, not a gauntlet.

**Live proof:** `enemies.json:14404` "Goblin Pack" — `nextEnemy` at `:14440` wrapping another at
`:14447`, `teamNumber: 1` on all three, `spawnRate: 1`. A scan of all 1520 TFR entries and 464
common entries found **exactly one** chained enemy. Treat the feature as *shipped but never
exercised*.

### 4.2 The one true blocker — the avatar crash

`DuelScene.java:755` calls `enemy.getAvatar(i)` → `CharacterSprite.java:314-316` → `avatar.get(i)`,
which throws `IndexOutOfBoundsException` for `i >= size`.

Measured across `res/adventure/common/sprites/enemy/`: **493 atlas files** — 491 with exactly one
`Avatar` region, `goblin_group.atlas` with **3** (which is precisely why Goblin Pack works), and
`monstrosity/umber_hulk.atlas` with **zero**.

**Fix (~10 lines):** add `getAvatarCount()` and clamp —
`enemy.getAvatar(Math.min(i, enemy.getAvatarCount() - 1))`.

> **The zero-region case breaks the naive clamp**: `getAvatarCount() - 1` is `-1` on
> `umber_hulk.atlas`, and a null return then NPEs at `DuelScene.java:756`
> (`enemyAvatar.flip(true, false)`). No `enemies.json` entry references that atlas today, so it is
> latent — but **skip the avatar wiring entirely when the count is 0.**

### 4.3 Two cosmetic defects that surface immediately

- **Nameplates:** `DuelScene.java:754` overwrites *every* seat's name with the head sprite's, and
  the in-code comment admits it: `//... (only supported for 1 enemy atm)`. Gate on `i == 0` (~2
  lines) and each seat keeps its own name; the engine de-dupes to "2nd …", "3rd …".
- **Rewards:** only the head enemy's `rewards[]` pay out. For a set-piece assault this is arguably
  *correct* — one fight, one payout, tune the head enemy up. Making packs feel like "3 kills" is a
  separate ~1-day job that drags quest-tag matching in with it.

### 4.4 `teamNumber` — a design decision, not a detail

`EnemyData.teamNumber` defaults to **−1** (`EnemyData.java:49`), and `Game.java:357-364` gives any
player arriving with −1 **its own fresh team**.

- `teamNumber: 1` on both AI seats → **allies**; the player must kill both. (What Goblin Pack does.)
- Left unset → **three-way free-for-all**: the AIs attack each other, and one of *them* can win.

That is the silent default, so it is an easy and confusing accident. **For a defended town, set
`teamNumber` explicitly on every seat.**

### 4.5 Recommendation

For the user's actual goal — a harder town-assault fight — **a buffed single opponent with starting
permanents gets most of the way for a fraction of the cost**, and is available today with zero code
changes. The 1-vs-2 path is genuinely cheap now (the avatar clamp plus a launcher, roughly a day),
but it carries the cosmetic tax above and, unfixed, the reward semantics of a single kill.

---

## Suggested build order

1. **Avatar clamp + nameplate gate** (~1 hour). Turns a crash into a working feature and unblocks
   all experimentation. Worth doing regardless of anything else here.
2. **Star placement, data-only** (`radiusFactor: 0`, R = 45, five `colorless.json` entries). Verify
   across seeds with the existing `[TFR-PoiPlacement]` logging before touching code.
3. **Persisted star-position field on `World`** + the loss check in `onMageArrived()`'s tail.
4. **Roads** for the star shape (three lines, and it fixes reachability for free).
5. **Bespoke maps and icons** — the largest art cost, and entirely deferrable behind steps 2–4.
6. **Town assault** (MOD_SCOPE #87) — the biggest design question, and the one least constrained by
   existing code.

## What was NOT verified

- No in-game playtest of any of this.
- The biome-safe-radius numbers replay the real claim loop in a harness, but are not the running
  game.
- The WFC structure pass was not modelled, so how often a star tile lands on a collision tile is
  *unquantified* — that needs a playtest or a debug build logging `isColliding()` at the five tiles.
- Reachability end-to-end (whether a town ringed by mountains is actually enterable) was traced only
  as far as `WorldStage.handlePointsOfInterestCollision`.
- The "AI ground control reaches the centre in ~225–290 in-game days" figure is arithmetic from the
  tuning constants, not observed.


---

## Re-verification 2026-09-02 (post Forge 09.01 merge, rounds 77-86)

Every claim was re-read against HEAD (round 85/86). Line citations above are stale by design;
current lines are given here where they matter.

**Part 1 - map generation: all claims hold.** `generateNew()` now spans `World.java:827-1650`; the
placement formula (1062-1072), the three constraints (1074, 1082-1088), the post-placement terrain
overwrite, the `radiusFactor: 0` math, the biome pentagon and the Spawn/campfire position are
unchanged and nothing in rounds 77-86 or the merge touched world generation. Two additions: the
doc omits the y line of the formula (`World.java:1067-1071`, with the y flip), and on an 8x8-box
overlap the loop first tries a 3x3 one-tile nudge (1091-1107) before rejecting. The 7.8-degree
anchor spread was not recomputed; the 45-tile radius and the seed harness were not re-run.

**Part 2 - game over: holds; two defects found and FIXED in round 86.** `triggerGameLost` is now
`WorldStage.java:693-713` (permadeath comment 679-684); the Capitol-defense loss branch is 500-513;
the poll is `GameStage.java:503-504` and is gated on `!isDialogOnlyInput() &&
!advFreezePlayerControls` (the doc says "every frame"); Path B is `TerritoryControl.java:2011-2030`.
Found: `pendingCapitolDefenseMage` was never reset on Load/new game, and Path B did not clear
`suppressDefeatGoldLoss` (round 78 cleared it on Path A only) - both fixed. Also: the Color Defeat
fizzle sits ABOVE the Capitol branch, so a defeated color's mage reaches neither path;
`defeatColor()` changes ownership outside the tail (safe for a loss check, it only reduces AI
holdings); the merge's 1-second `startPause` before `loadPOI` suppresses the Path A poll for that
second. Implementation advice: put the star check in the tail AFTER Path B with an `else if` so two
dialogs cannot stack, count AI-held star towns by name prefix (a swept town is renamed
"Waste Town ..."), warn per fallen arm with `GameHUD.addNotification(msg, true)`, and log a
`[TFR-GameLost]`-style line.

**Part 3 - AI starting cards: holds, with two corrections.** (1) "All 20 color-town maps contain
zero enemy objects" was wrong when written: `plains_town.tmx` carries one gate NPC (enemy.tx id 54,
Apprentice White Wizard, dialog-only); MOD_SCOPE #87 repeats the wrong sentence. (2) "The launcher
template exists twice" overstates it: `startForcedCapitolDuel` is the clone-and-mutate template;
`startChestDuel` launches directly without cloning. New findings: a PER-SEAT starting-card channel
already exists - `DuelScene` applies each seat's `EnemyData.equipment` item effects inside the seat
loop, gated by `eventRules.allowsItems` - so per-catalog starting cards may not need a new field;
`EffectData`'s copy constructor is lossy (drops `startBattleWithCardInCommandZone`, `moveSpeed`,
`goldModifier`, `cardRewardBonus`, `visionRadiusMultiplier`) - fix before cloning effects per
seat; enemy-equipment `opponent` effects are added after the human's effects were already applied
(latent upstream bug, harmless today); `MapDialog`'s `setEffect` action can arm an effect at
runtime. The merge's `renderTransitionScreen` toggles in `DuelScene.enter/leave` are inert for
seat setup.

**Part 4 - 1-vs-N: build-order step 1 is DONE (round 77, commit 4ef14cecd9a).** The avatar clamp
shipped inside `CharacterSprite.getAvatar(int)` (zero-region case guarded; `getAvatarCount()`
exists but nothing calls it) and the nameplate gate `i == 0` shipped as proposed; the flip now acts
on a `TextureRegion` copy. The atlas census (493 / 491 single-frame / goblin_group 3 / umber_hulk 0)
holds - measure with a whitespace-tolerant match, 61 atlases are CRLF. Every seat still draws the
HEAD sprite's portrait; distinct faces need a multi-frame pack atlas. The chain walk caps at 8
seats. Goblin Pack remains in no biome list (it is in `config tables/enemies.csv`), so 1-vs-N is
still unobservable in play. Steps 2-6 are not started. Cheapest smoke test: list "Goblin Pack" in
one biome temporarily. Add a `[TFR-Duel]` seat log (index, name, teamNumber, avatar frame) when the
first pack content is authored.

**Open decisions (unchanged, still the real blocker for steps 2-6):** anchor (fixed Capital POI vs
Chapter-1 castle), radius, visibility from turn one, starting ownership, restored vs ruined state,
win threshold and its Color Defeat interaction, player capture/retake, count.
