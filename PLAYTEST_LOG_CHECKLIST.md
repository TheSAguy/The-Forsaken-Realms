# Playtest Log Checklist — Roaming-Enemy Bestiary / Tier / Territory round (2026-08-10)

**Read this if:** the user says they've played a session on the Gaming PC (or any PC) and wants
you to confirm the 2026-08-10 roaming-bestiary/tier/War-boss/re-theme/capture-odds work actually
functioned in-game. This file is self-contained — you don't need memory of the session that built
this, just `MOD_CHANGELOG.md`'s "Roaming-Enemy Bestiary + Mage Difficulty Tiers" and its follow-up
entries (search for those headings) if you want the full design rationale for anything below.

**Why this exists:** several of that round's own bugs (11 unreachable enemies, a bricked spawn
pool, etc.) were only caught by direct data audits, not by playing — the user correctly pointed
out that in-game observation alone can't distinguish "working as intended" from "silently broken
in a way that just looks like normal variety." The diagnostic log lines below exist specifically to
close that gap. They are new, deliberately temporary instrumentation — not a permanent feature.

## Step 0: find `forge.log`

Every log line below is written via plain `System.out.println(...)`, which this codebase's
`ExceptionHandler` redirects into `forge.log` (`ForgeConstants.LOG_FILE = USER_DIR + "forge.log"`)
in addition to the console. `USER_DIR` is the game's *deployed/installed* copy's profile directory
— **not** this git checkout — and its exact location is genuinely machine-specific:

- On the home PC this session was built on, the deployed copy lives at `E:\GAMES\FORGE\` (per
  project memory) — check there first if you're running on that machine.
- On any other machine (the Gaming PC included), **do not guess** — if it's not in an obvious
  place near wherever the user actually launches Forge from, ask the user directly where their
  Forge install/profile directory is, or have them locate `forge.log` themselves (it sits next to
  the other profile data — decks, save games, etc.) rather than spending a long time guessing paths.
- If a session has been running a while, older content rotates into `forge1.log`, `forge2.log`,
  etc. (`n == 0 ? "forge.log" : "forge" + n + ".log"`) — the most recent play session is usually
  still in the plain `forge.log` unless the user restarted the client multiple times since playing.

Once you have the path, every check below is a `grep` (or PowerShell `Select-String`) against that
file. All new log lines share the prefix `[TFR-` (originally for "The Forsaken Realms" - kept
as-is after the 2026-08-25 rename to "The Forsaken Realms" since renaming it would mean touching
every already-existing `[TFR-...]` log line in the codebase, not just this doc) so the whole batch
is findable at once: `grep "\[TFR-" forge.log` (PowerShell: `Select-String '\[TFR-' forge.log`).

## What each tag means and how to check it

### `[TFR-Spawn]` — ordinary roaming spawns (WorldStage.java)
Logged on **every** roaming spawn that isn't a War-tier boss (see below) — this will be the bulk
of the `[TFR-` volume, one line per spawn: `[TFR-Spawn] <name> (tier=<tier>, colors=<colors>) in
<biome> territory (rank=<player rank>)`.

**What to check:**
- `grep "\[TFR-Spawn\]" forge.log | wc -l` — confirms spawning is happening at all (should be
  dozens+ for any real play session).
- **Confirm the 11 previously-unreachable colorless enemies now spawn**: `grep "colors=C)" forge.log`
  (or PowerShell `Select-String 'colors=C\)'`). You should see at least one of: `Graaz`, `Hope of
  Ghirapur`, `Karn`, `Liberator`, `Omarthis`, `Syr Ginger`, `The Dawning Archaic`, `The Peregrine
  Dynamo`, `Traxos`, `Ulamog`, `Zhulodok` — IF the player spent meaningful time on player-owned or
  neutral/colorless territory (these were added to `colorless.json`/`player.json` specifically, not
  the 5 AI-color biomes). Seeing zero isn't necessarily a bug if the player never visited that
  terrain — check where the player actually spent time before concluding anything's wrong.
- **Confirm tier variety**: `grep -o "tier=[A-Za-z]*" forge.log | sort | uniq -c` — should show a
  mix, skewed toward `Common`/`Uncommon` early in a save (low player rank) and only including
  `Rare`/`Mythic` once rank has climbed (20+ wins for Uncommon-and-below ceiling to lift, 60+ for
  Rare, 150+ for everything — see `PlayerStatistic.rank()`). A brand-new save showing only Common
  is correct, not a bug.
- **Confirm player-territory variety specifically**: `grep "in player territory" forge.log | grep -o
  "^\[TFR-Spawn\] [^(]*"` — should show more than just the original ~49 Wasteland names if the
  proximity-intrusion mechanic (below) or the colorless-tag fix both did their job.

### `[TFR-Intrusion]` — proximity/reputation spawn substitution (WorldStage.java)
Logged only when a nearby foreign-color town/capital/castle actually substitutes its color's
roster for a spawn roll: `[TFR-Intrusion] <original biome> territory -> <foreign color> intrusion
fired (chance=<rolled chance>, status=<reputation tier>)`.

**What to check:**
- This only fires within ~40 tiles of a foreign-color town/capital/castle (`
  SPAWN_INTRUSION_RADIUS_TILES` in `TerritoryControl.java`), and only 12.5–62.5% of eligible rolls
  depending on reputation tier with that color (0% at Partner — should **never** see a `status=
  PARTNER` line; if you do, that's a real bug). If the player never went near a border, seeing zero
  lines here is expected, not broken.
- If you do see hits, sanity-check the `status=` value against what the player's reputation with
  that color actually was during the session (ask the user, or check their save's World Standings
  screen) — `status=WAR` lines should be the most common if present at all, since War-tier gets the
  highest multiplier (2.5x vs. the 1x baseline).

### `[TFR-WarBoss]` — the 38 Shandalar Old Border bosses (WorldStage.java / TerritoryControl.java)
Logged only on an actual War-tier boss spawn: `[TFR-WarBoss] <name> spawned in <color> territory
(War-tier)`.

**What to check:**
- This is intentionally very rare (4% chance, and only even rolled when standing in/near
  territory of a color the player is genuinely At War with — reputation ≤ -80). **Do not expect to
  see this in a short or casual session** — if the player wasn't at War with anyone, zero hits here
  is completely expected, not a bug. Only worth investigating if the player specifically confirms
  they spent real time at War-tier standing with some color and never saw one.
- If it does fire, cross-check the boss name against that color's list in
  `TerritoryControl.WAR_TIER_BOSSES` (in `forge-gui-mobile/src/forge/adventure/util/
  TerritoryControl.java`) to confirm it's a legitimate pick for that color, not a mismatch.

### `[TFR-ReTheme]` — dungeon content re-theming to current territory owner (TerritoryControl.java)
Logged only when a dungeon's hardcoded enemy placement actually gets substituted:
`[TFR-ReTheme] <POI name> (home=<original color>, now=<current color>) -> <substituted enemy>`.

**What to check:**
- This only fires for a dungeon whose surrounding land has **changed hands** since world
  generation (a capture, or territory expansion swallowing it) — on a fresh save where nothing's
  been captured yet, zero hits is correct. Ask whether the player captured any towns/territory
  near a dungeon before concluding this isn't working.
- If it fires, the `home=`/`now=` colors should genuinely differ (that's the whole gating
  condition) — if you ever see a line where they're equal, that's a real bug (shouldn't be
  possible given the code, but worth flagging if seen).

### `[TFR-CaptureOdds]` — tier-weighted town-fight capture resolution (TerritoryControl.java)
Logged on every enemy-color-town capture attempt (an AI mage reaching a town owned by one of its
declared enemies): `[TFR-CaptureOdds] <color> mage (tier=<tier>, chance=<chance used>) attacking
<town> (<defender color>) -> CAPTURED` or `-> REVERTED to neutral`.

**What to check:**
- `grep "\[TFR-CaptureOdds\]" forge.log` — confirms the mechanic fires at all (needs Territory
  Control + cross-color targeting active, which needs `territoryControlEnabled` on and at least
  two AI colors actually fighting each other, not just the player).
- Confirm `chance=` matches the tier shown: Common→0.1, Uncommon→0.3, Rare→0.7, Mythic→0.9 (see
  `TerritoryControl.attackerWinChance()`). A mismatch here would be a real bug.
- Over enough samples, `CAPTURED` should trend toward the `chance=` value shown per tier (e.g.
  mostly `CAPTURED` for Mythic-tier mages, mostly `REVERTED` for Common-tier ones) - a handful of
  samples won't be conclusive either way, this needs volume to judge.

## What's NOT covered by logging (check these directly in-game instead)

- **The 12 bosses' 90% Rare / 10% Mythic drop odds** (`Dark Enchanter`, `Emrakul`, `Kozilek`,
  `Ancient Silver Dragon`, `Guardian Angel`, `Myr Superion`, `Sliver Queen`, `Sorin`, `The Hydra of
  Shandalaar`, `Torturer`, `Valyx Feaster of Torment`, `Wounded Sliver`) — no log line was added for
  this (it lives inside the shared `RewardData` reward-granting path, which every reward in the
  game flows through — adding logging there would spam the log for every gold/card/item reward in
  the whole game, not just these 12). **Check this by having the player defeat one of these bosses
  and look at the actual reward screen** — that's the real, reliable confirmation (both the roll
  *and* the item resolving to something real, in one observation), not something worth adding
  broader logging for.
- **Item obtainability / enemy reachability in general** — already fully data-audited (0 gaps as
  of this round, see `MOD_CHANGELOG.md`'s "Final pre-playtest audit" entry). Playtesting can't add
  meaningfully to that specific question; it was answered exhaustively already.

## If something looks wrong

Don't guess at a fix from the log alone — the log tells you a mechanic didn't fire or fired
unexpectedly, not *why*. Go read the actual method named in the relevant section above
(`WorldStage.handleMonsterSpawn()`, `TerritoryControl.reThemedEnemyFor()`, `TerritoryControl.
onMageArrived()`, `TerritoryControl.rollWarTierBoss()`) and reason from the real code, the same way
this round's own audits did — re-derive, don't assume the earlier design notes in
`MOD_CHANGELOG.md` still describe the current state exactly if anything's changed since.

## Cleanup note

Once the user is satisfied these all work, these `System.out.println` lines are safe to remove if
the log volume (especially `[TFR-Spawn]`, which fires on every roaming spawn) becomes annoying -
they were added purely for this verification pass, not as a permanent feature. Ask before removing
them, though - the user may want to keep them around for a while longer.
