# START HERE — read this first, every session

**You do NOT need to read previous chat threads.** They are expensive and they are not the source
of truth. Everything a session needs is in five files, and they are kept current by a standing rule
that every round updates them in the same action as its commit. If you find something missing, fix
the docs rather than going back to chat history.

Read in this order, and stop when you have what you need:

| Read | For |
|---|---|
| **this file (`CLAUDE.md`)** | ground rules, release workflow, deploy path, build commands |
| **`MOD_SCOPE.md`** | the feature list — 101 numbered items with live status. Start at the Currency line |
| **`MOD_CHANGELOG.md`** | the engineering log. Newest rounds at the **bottom**. ~15k lines — read the last few rounds, then grep by keyword |
| **`CORE_ENGINE_CHANGES.md`** | every stock-engine file this mod edits, for upstream-merge conflict work |
| **`ANDROID_RELEASE.md`** | the authoritative Android release procedure. Read before ANY Android work |

Then run `git log --oneline -15` and `git status` — those two tell you the rest.

## STATE 2026-09-02 EVENING (round 86) - READ THIS FIRST, DO NOT REPEAT WORK

**Token budget warning.** This session hit the 5-hour usage limit THREE times running multi-agent
Workflows (each attempt burned ~1.3M tokens before dying). Do NOT relaunch review/research
workflows. Work solo or with single agents; the user asked for economy.

Done today (committed):
- Round 83 `d76f3f343ff`: NG+ and Arena-coin log lines (Array.size fix), Android-build trap docs.
- Round 84 `4509df9c0ae`: **upstream merge @ c817743ecbd = Forge_2's Snapshot 09.01**. 4 conflicts
  resolved, CON->CFX edition sweep (20 plane refs), engineBuildVersion 09.01, packager marker.
- Round 85 `99b6856e9a9`: dungeon audit - 94 stone/wood drops relocated (verified in bounds, no
  collision, no overlap), 11 teleports retargeted, 50 card rewards re-keyed cardName, Mantle of
  Denial typo, 3 boosters, 1 effect, 1 dialog. MOD_SCOPE #101 updated. Left by design: 4 zedruu
  drops, inn_cave_river_entrance enemy id 16 (empty enemy name).
- Round 86 (this commit): World.generateNew resets fogOfWarStage2Revealed; DungeonRotation.
  resetSessionState() + TerritoryControl.clearPendingCapitolDefense() from WorldStage.clearCache();
  triggerGameLost clears suppressDefeatGoldLoss for every loss path; [TFR-MageCap] de-duplicated;
  NG+ log labels fixed; build_standalone.py daily-stamp guard (--allow-base-mismatch) + early
  launcher checks. STAR_TOWNS_RESEARCH.md has the re-verification addendum.
- Live folder: PACKAGE_OK with the round-85 jar (built 11:29). **After round 86 the jar must be
  rebuilt** (`mvn -pl forge-gui-mobile-dev -am package -DskipTests -o`, ~15 min, ALWAYS backgrounded)
  and `python standalone-packaging/build_standalone.py` re-run. Check the live jar's mtime against
  the round-86 commit before assuming it was done.
- Game log reviewed (forge.log 10:54-11:09, idle 139-day Viashino game): no exceptions; only the
  MageCap spam (fixed). Session #11 (Opus) stood down; its save backup
  `1_save_slot.sav.prededit2.bak` in the profile dir must NOT be deleted.
- Code review: only the newgameplus and android/packaging lenses ever completed. Never run:
  merge-integration, save-compat, economy, ui-dialogs, territory-spawns, data-integrity. If wanted,
  run ONE lens as a single agent.
- NOT pushed. Standing rule: the user playtests the live folder first, then `git push origin
  main:master`.
- Upstream moved 5 commits past c817743ecbd; take them with the next engine update + Forge_2
  reinstall. Optional: upstream added MSH to common starterEditions; TFR's list untouched.
- Round 104 (2026-09-04): data-only - card shop quest popup (stock = unlocked sets, cheaper shops, 100g+5
  shard refund) + GUIDE.md wording. Packager-only chain (no Maven).
- Round 103 (2026-09-04): Warden soft-lock fixed (rune node -> reward card -> Thank you -> portal; coin branch
  removed from spawn.tmx); skip-intro 'all' gift grants directly + counts the Ring as visited (+5 life).
  Lesson: a RewardScene opened from the new-game intro dialog is lost. Needs a NEW game.
- Round 102 (2026-09-04): Ring gifts fixed (config difficulty, not the player's partial copy) and shown as
  RewardScene cards; Llanowar also gives the Warden's coins (rune stays with the Warden; coin node retired in spawn.tmx);
  nav arrows via navPOIFilter 'tagged'; ringCityTownExclusionTiles 14; VICTORY = 5 Ring Cities + 5 colors
  DEFEATED (castles), capitals only halve mages; [+Life] glyph notifications. Needs a NEW game.
- Round 101 (2026-09-03): NEW STORY OPENING - quest 75 'Oaths at the Ring' (53 -> 75 -> 30): start with
  nothing (ringGiftStart), five Ring Cities hand over the difficulty's gold/shards/wood/stone/items via the
  grantRingGift dialog action; life ladder 20/15/10/5 (+5 Ring). quests.json was re-serialized (strict JSON
  now). Needs a NEW game. Awaiting playtest + the user's read of the story text.
- Round 100 (2026-09-03): Ring Cities named Benalia/Tolaria/Urborg/Shiv/Llanowar; world-gen roads min 1 /
  max townMaxRoadLinks 5 (Ring/Spawn edges uncounted); capital Attack = 1v2 Archmages -> player town +
  halved mage cap; victory = 5 Ring Cities + 5 capitals (triggerGameWon); ring life bonus; [TFR-Perf];
  portrait layout fixes. Story/tutorial rewrite PENDING user answers (see MOD_CHANGELOG r100 notes).
- Round 99 (2026-09-03): Ring Towns - weekly per-color targeting cooldown, x1.25 weight when among the
  5 nearest, never sacked; roads back to nearest-neighbor minus 25% + capture roads go to the closest
  SEAT-connected town (road flood fill); AI-vs-AI guard fights (table in MOD_CHANGELOG); 1v2 assault at
  AI-held Ring Towns (test); TEST targeting hook REMOVED. Needs a NEW game. Awaiting playtest.
- Round 98 (2026-09-03): star 17 tiles; townMinSpacingTiles 10; per-color road trees; repaint keeps
  water; townMaxTerritoryRadius 450; TEST-ONLY debugStarTownTargetChance 0.5 (REMOVE after testing).
  Needs a NEW game. Awaiting playtest.
- Round 97 (2026-09-03): Center Towns at 20 tiles, always functioning-neutral with their own art (map +
  minimap), 24-tile no-other-town zone. Awaiting playtest on a NEW game.
- Round 96 (2026-09-03): Center Towns also road-linked to each other (full 15-edge star mesh).
- Round 95 (2026-09-03): Center Towns (MOD_SCOPE #102) - 5 star towns around the campfire with
  the user's castle art, spoke roads, loss at 3 held by one color. NEW WORLDS ONLY. NOT playtested.
- Round 94 (2026-09-03): defenders follow kill decay, life x1/1.5/1.75/2 by difficulty, -4 rep on
  attack and -8 on capture (spread), former owner dispatches a mage on capture. NOT playtested.
- Round 93 (2026-09-03): one assault per town per week (aiTownAssaultCooldownDays), the barred
  dialog states the remaining days. NOT playtested.
- Round 92 (2026-09-03): AI guard dots (28 days/level, 4 levels, capitals two Archmage) drive the
  assault defender tier; clock starts at first sight (save load/capture). NOT playtested.
- Round 91 (2026-09-03): assault win captures the town (restored-ruin state). Dungeon-on-load report
  was by design (single-enemy cave). OPEN questions to the user: tapped-land intent; AI guard-dot
  tier system spec (see MOD_CHANGELOG round 91) - do not build the dot system before they answer.
- Round 90 (2026-09-03): SAVE-WIPE REGRESSION from round 88 fixed (EffectData serialVersionUID
  pinned to the v1.04 value; nine other save-bound classes pinned). Saves written by the round-88
  build (today 10:02-10:10) are unrecoverable; pre-update saves load again. Rebuilt + PACKAGE_OK.
- Round 89 (2026-09-03): user's player-biome art update (player_terrain/doodads/structures PNGs,
  same dimensions as before). `*_original.png` backups sit UNTRACKED in the plane folder - they
  ship in the live folder until moved; do not delete them without asking.
- Round 88 (2026-09-03): multi-slot research + settings.json researchDays/researchShardCost;
  War town assault first cut (Attack/Leave at a War town, random roamer, tapped basic land via new
  engine plumbing). NOT playtested. Follow-ups the user announced: defender tier system, town
  capture. Live folder rebuilt at the end of that round - verify PACKAGE_OK.
- Round 87 `(see git log)`: `[TFR-Life]` logging at every life mutation - the diagnostic for the
  next item; live folder rebuilt with it (check PACKAGE_OK).
- OPEN USER REPORT (2026-09-02 evening): player life total wrong after losing a fight and
  loading. Screenshots in C:/Users/User/Pictures/Screenshots/LOG. Investigation notes, if any,
  are in MOD_CHANGELOG round 86/87.

## Where things stood after v1.04 (2026-09-02 morning)

**v1.04 is released.** PC + Android, both live at
`https://github.com/TheSAguy/The-Forsaken-Realms/releases/tag/tfr-v1.04`. Tag `tfr-v1.04` is on
`7018e12235f`, `main` is level with `origin/master`, working tree clean, and the live folder at
`F:\FORGE\TFR-Standalone\The Forsaken Realms\` is built and `PACKAGE_OK`. Rounds 62-82 all shipped
in that release, which was the first push since v1.03.

## What v1.05 starts with — NOT open to reordering

**Step 0 is the upstream engine merge.** Standing user rule as of 2026-09-01: always take the latest
`upstream/master` BEFORE cutting a release, as its own round. Measured 2026-09-01 at **34 commits /
1,812 files / 174 `.java`** behind. See the "Release rule" section below for why it must be its own
round, and note it **blocks packaging until the user reinstalls `E:\GAMES\Forge_2`** at the matching
engine version — only they can do that step, so raise it early rather than discovering it mid-build.

## Open items

- **MOD_SCOPE #101** — the resource-drop placement sweep is 25 of 75 done. 50 clustered `+5/+5`
  drops remain in 49 maps: 2 out of bounds and uncollectable, 15 partially buried in collision, 33
  merely stacked. Full triage is in the item.
- **MOD_SCOPE #84 / #85 / #87** — the only Not Started items. #87 (More Attacking Options) has real
  research behind it in `STAR_TOWNS_RESEARCH.md` and carries the AI end-game objective ("capture the
  centre of the map"). #84 has four named targets: Mine Upgrades, City Walls, Mage War Camp, Armory
  Upgrade.
- **MOD_SCOPE #98** — 1-vs-N duels work at the engine level but have **zero reachable content**:
  one chained enemy exists in 1,520 and it is in no biome spawn list. Making it reachable means
  authoring content, most naturally as part of #87.
- **Shipped in v1.04 but never playtested by the user**: the Arena bracket coin payout, #94 Armory
  rarity, #95 Capitol cooldown, #96 New Game+, and the round-79 Skip Tutorial text (new games only).
- **Android has real testers** (24 downloads on the v1.03 APK) and the user has no Android device —
  feedback arrives via Discord.

## Hard-won lessons that will bite you again

- **Every class that goes into a `.sav` needs an explicit `serialVersionUID`, and changing one is a
  save-format change.** Round 88 added a field to `EffectData` (embedded in every inventory
  `ItemData`); Java's derived UID changed and EVERY existing save loaded with an empty inventory
  behind a "Data Migration completed" dialog. All ten save-bound classes are pinned since round 90.
  Before packaging a build that touches `forge/adventure/data`, `player`, `pointofintrest`, `world`
  or the two controllers, LOAD a v1.0x save and check the inventory.
- **Read Maven's own exit code, never a pipe's.** `mvn ... | grep ...; echo $?` reports *grep's*
  status. A round-78 compile reported success while Maven had failed. Redirect to a file and read
  `$?` immediately.
- **The Android release build leaves the desktop build unable to compile.** `mvn -pl
  forge-gui-android -am clean install -P android-release-build -Dmaven.repo.local=C:/m2` cleans the
  shared modules and rebuilds them against a different local repo, leaving `forge-game` /
  `forge-core` `target/classes` partial. The next ordinary compile then fails with `cannot find
  symbol` / `cannot access ... NoSuchFileException` in files nobody edited. Round 81 blamed this
  exact symptom on two concurrent Maven builds; that was wrong (reproduced 2026-09-02 with ONE
  Maven, right after the v1.04 Android release). Fix: `mvn -pl forge-gui-mobile -am clean compile
  -DskipTests` - the `clean` is load-bearing. Still never run two builds at once, but when untouched
  files stop compiling, suspect a stale `target/` first.
- **Another Claude session may be live on this same checkout.** On 2026-09-02 two sessions worked
  the tree at once; the tells were a `java.exe` running Maven that this session had not started, and
  doc edits appearing in `git status` unbidden. Before committing, merging or building, run
  `ListAgents`; if a peer session is listed, message it to stand down and wait for its build to
  exit. Never kill a build you did not start.
- **`--zip` packaging always does the full stock-asset copy** and takes well over ten minutes. Do
  not give it a short timeout — killing it mid-run strips `PACKAGE_OK.txt` and leaves the live
  folder in the half-rebuilt state that marker exists to catch.
- **Read `PACKAGE_OK.txt` before telling the user it is safe to play.** File-existence checks are
  not a substitute; the packager deletes it first and writes it last for exactly this reason.
- **Edit saves with Java, never Python.** See `dev-tools/save-editing/README.md`.
- **Write the changelog entry in the SAME action as the code commit.** Rounds 73-76 and then round
  78 all shipped with detail only in commit messages and had to be backfilled. A thorough commit
  message is not a substitute and reads as done when it is not.
- **An "empty set means everything" convention must be read through its predicate at every site.**
  A raw `Set.contains()` near one is a latent bug — that pattern was a save-corrupting release
  blocker caught in round 78.
- Diagnostic logging is not optional. Anything probabilistic, AI-driven, or off-screen gets a
  `[TFR-<Name>]` line **as part of building it**. Round 82 exists because a feature shipped without
  one and a user report could not be diagnosed from `forge.log`.
# This Repo

This is a fork of [Card-Forge/forge](https://github.com/Card-Forge/forge) (the open-source MTG
engine) used to build a personal Adventure-mode mod called **"The Forsaken Realms"**. The user
works across two machines and may or may not have git sync available at any given time - **read
`MOD_SCOPE.md`, `MOD_CHANGELOG.md`, and `CORE_ENGINE_CHANGES.md` before touching any mod-related
code**, since a prior Claude Code session may have made changes here that this session doesn't
have in its own memory.

- **`MOD_SCOPE.md`** — the feature wish-list: what we want to build, current status per item.
- **`MOD_CHANGELOG.md`** — the engineering log: what's actually built, how it works, key
  gotchas. This is the source of truth for implementation details, not chat history.
- **`CORE_ENGINE_CHANGES.md`** — tracks every edit to a *stock* (non-mod-plane) engine file, so
  that when the user pulls a Card-Forge/forge update (upstream ships several a week), it's fast
  to cross-reference what upstream changed against what this mod already changed in the same
  file, instead of re-diffing everything from scratch.

## Ground rules for mod work

- All mod features are **opt-in per-plane config flags** on `ConfigData.java`
  (`forge-gui-mobile/src/forge/adventure/data/ConfigData.java`), defaulting to `false`, turned
  on only in `forge-gui/res/adventure/The Forsaken Realms/config.json`. Never make a mod
  feature apply unconditionally - it must not affect Shandalar or any other stock plane.
- `The Forsaken Realms/config.json` is a **full standalone copy** of `common/config.json`, not
  a small override - Forge does not merge per-plane config with common's. See
  `MOD_CHANGELOG.md` for details.
- Mod code changes live under `forge-gui-mobile/src/forge/adventure/`. Mod plane data/assets
  live under `forge-gui/res/adventure/The Forsaken Realms/`. Both need to travel together
  (e.g. via git) for the mod to actually work on another machine - copying just one half is not
  enough.
- **Prefer storing custom/edited assets and data under `forge-gui/res/adventure/The Forsaken
  Realms/`, not `common/`, whenever the engine's plane-aware file resolution makes that possible**
  (the same "full copy, not merge" override pattern already used for `config.json`,
  `points_of_interest.json`, `world.json`, the biome jsons, and every custom `.png`/`.atlas` this
  mod has added) - keeps the mod self-contained in one folder, which matters when it's eventually
  shared. Only touch a genuinely shared file (like `forge-gui/res/languages/en-US.properties` -
  Forge's localization strings have no per-plane override mechanism) when there's truly no
  plane-scoped alternative, and note it as an exception in `CORE_ENGINE_CHANGES.md` when you do.
- **Any edit to an existing engine file outside `forge-gui/res/adventure/The Forsaken Realms/`
  (or a new file added outside that folder) needs a matching entry in `CORE_ENGINE_CHANGES.md` in
  the same round** - same standing requirement as keeping `MOD_CHANGELOG.md` current, just scoped
  to upstream-conflict-relevant changes specifically.
- After committing changes to `MOD_SCOPE.md` or mod source files, push to `origin` without
  waiting to be asked (standing user preference). This was briefly reversed on 2026-08-13 after
  the user hit ~90% of their GitHub Actions monthly minutes cap (every push was triggering CI) -
  user addressed it on the repo side (trimmed/disabled the relevant workflow(s), including this
  same round's `.github/workflows/test-build.yaml` auto-trigger removal) and explicitly asked to
  resume pushing the same day. Back to the original standing preference.
- `origin` is the user's own fork, **`TheSAguy/The-Forsaken-Realms`** (renamed from
  `The-Forgotten-Realms` on 2026-08-27 with the game's rebrand; GitHub still redirects the old
  URLs). Local branch is `main` but the remote default is `master` - push with
  `git push origin main:master`. `upstream` is the original `Card-Forge/forge` project, for
  pulling in engine updates only - never push mod work there. Note `gh` in this repo resolves to
  UPSTREAM by default, so always pass `-R TheSAguy/The-Forsaken-Realms`.
- **Add a greppable diagnostic log line for any mechanic that's hard to observe by just playing**
  (standing practice, user request 2026-08-13) - anything probabilistic, AI-driven, or that fires
  rarely/off-screen (combat odds, AI targeting decisions, scaling formulas, timers). Follow the
  established `[TFR-<Name>]` tag convention already used by `[TFR-GuardFight]` (attacker tier,
  guard tier, computed chance, outcome), `[TFR-DayNight]`, and `[TFR-CaptureOdds]` - one line per
  relevant event, with enough values printed to verify the mechanic's actual behavior from
  `forge.log` alone, without needing to catch it live on screen. Add this as part of building the
  feature, not as an afterthought - it's what lets a future session validate a change the user
  can't easily reproduce themselves.

## Release rule: take the upstream engine update FIRST

**Standing user preference (2026-09-01): always merge the latest `upstream/master` before cutting a
release.** It has to be its own round, planned ahead of the release rather than bolted onto it:

- An engine merge is large - measured 2026-09-01 at 34 commits / 1,812 files / 174 `.java` - and it
  swaps the rules engine underneath whatever was just playtested. **Everything must be re-tested
  after it.** Round 58 was the last one, and round 59 immediately after it was a playtest-fix round.
- **It blocks packaging until `E:\GAMES\Forge_2` is reinstalled** at the matching engine version.
  `build_standalone.py`'s first step verifies its jar version against the repo's and aborts on a
  mismatch. Only the user can do that step - flag it early, do not discover it mid-build.
- **Upstream clobbers our Android branding and version stamps** - `forge-gui-android/pom.xml`, the
  launcher icons, the splash art, `Zone.java`. `ANDROID_RELEASE.md` carries the revert-watch list;
  read it as part of the merge, not afterwards.
- Resolve `README.md` conflicts to OURS (it is the game's readme, not upstream Forge's).
  `CORE_ENGINE_CHANGES.md` exists to make the conflict pass fast - grep it per conflicting file.

So the release order is: **merge upstream -> reinstall BASE_INSTALL -> rebuild -> user re-tests ->
then tag and publish.** v1.04 shipped WITHOUT the merge by explicit user decision, because the merge
would have invalidated a full day of playtesting; it is the first work of v1.05.

## Build/toolchain

Maven + JDK are installed portably on each machine (not tracked in git). Verify with
`mvn -pl forge-gui-mobile -am compile -DskipTests -o` (add `-o` once dependencies are already
cached locally) before considering a change done.

## Deploy (live game folder at `F:\FORGE\TFR-Standalone\The Forsaken Realms\`)

**This is the folder the user actually plays.** The old `E:\GAMES\FORGE` three-jar splice-deploy
target is **retired** (since round 26, 2026-08-19) - don't touch it, and don't reintroduce `jar uf`
splicing: the standalone packaging script rebuilds the whole folder instead.

**Do not confuse the retired folder with `E:\GAMES\Forge_2`, which is very much live.** That is
`BASE_INSTALL` - where the user installs the latest **stock** Forge whenever we take an upstream
engine update. The packaging script *reads* the launcher shells and the installer-shaped `res/`
tree from it (`cardsfolder.zip` etc., which this repo does not hold in that shape), and its very
first step refuses to build if `BASE_INSTALL`'s jar version does not match the repo-built jar's.
So an upstream engine merge is **two** steps, not one: merge the engine code here, *and* have the
user install stock Forge at that same version into `E:\GAMES\Forge_2`. A version mismatch also
auto-forces the full stock-asset re-copy, so the first package after an engine update is slow by
design. Read-only input for us - never write to it.

Deploy loop, in order:
1. **Compile check** - `mvn -pl forge-gui-mobile -am compile -DskipTests -o -q`. Fast; catches
   errors and checkstyle before the slow step. Don't skip it - a Maven build that fails unnoticed
   is a real failure mode here (see the freshness guard below).
2. **Build the jar** - `mvn -pl forge-gui-mobile-dev -am package -DskipTests`. Produces the
   `forge-gui-mobile-dev-<ver>-SNAPSHOT-jar-with-dependencies.jar` *and* the launch4j exe carrying
   the TFR icon. This is the only jar the game ships - the three-jar problem is gone with the old
   deploy target.
3. **Package** - `python standalone-packaging/build_standalone.py`. Assembles
   `F:\FORGE\TFR-Standalone\The Forsaken Realms\` from `BASE_INSTALL` (stock engine shell) + the
   repo-built jar + `forge-gui/res/adventure/The Forsaken Realms/` + a git-derived overlay of the
   mod's non-adventure `res` edits (so future rounds' res edits are picked up automatically).
   The plane folder is **always** rebuilt fresh, so a changed resource needs no separate copy step -
   and unlike the old `cp -r` mirror, files deleted from the repo really do disappear from the live
   folder.
   - `--zip` **only** when building a release asset; it also forces the full stock-asset copy. No
     local zips are kept - upload it, then delete it from `F:\FORGE\TFR-Standalone\`.
   - `--full` forces the stock-asset re-copy on a local build - needed only for suspected local
     corruption. An engine-version change forces it automatically without the flag.
4. **Read `PACKAGE_OK.txt`** in the live folder before telling the user it is safe to play. The
   script deletes it first and writes it only after full verification, so its presence is the
   playability contract. **Never substitute file-existence checks for reading this marker** - a
   half-rebuilt folder looks complete right up until the game hangs forever on the stock splash
   (2026-08-21 incident).

Two guards the script enforces - know them before debugging a refusal:
- **Jar freshness**: it refuses to package a jar older than any `.java` under the built modules.
  Added 2026-08-30 after a failed Maven build got silently packaged and declared "safe to play".
  Plane resources are deliberately exempt - they are copied, not compiled, so a resource newer than
  the jar is legitimate.
- **Game-running lock probe**: it refuses to start if a jar in the live folder is locked, i.e. the
  game is open. It aborts *before* removing `PACKAGE_OK.txt`, so a refusal leaves the folder exactly
  as it was and still playable - just close the game and re-run.

Release order is unchanged and still applies on top of this: build + update the live folder, **user
tests it**, and only after their explicit pass does anything get pushed or published.
