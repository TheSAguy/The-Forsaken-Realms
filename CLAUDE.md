# START HERE — read this first, every session

**You do NOT need to read previous chat threads.** They are expensive and they are not the source
of truth. This file holds the standing rules and the current state; `MOD_CHANGELOG.md` holds what
every round built. If you find something missing, fix the docs rather than going back to chat history.

## NOW (rewrite this block each round; never append a new one)

**v1.23 "Fixes & Engine update" is the latest release** (tag `tfr-v1.23` @ `69797542f59`, 2026-10-21; rounds
689-701). Unreleased: rounds 702-708 (`git log --oneline tfr-v1.23..HEAD`; detail in `MOD_CHANGELOG.md`). Next version: 1.24 /
12400. Engine = the 10.21 daily (round 701); next merge from upstream after `bc67f70ffbc`; BASE_INSTALL is that
daily, so packages need no `--allow-base-mismatch`. Next free round: 709. Log sharing (round 698) posts to Discord through a webhook
kept OUT of git in `C:\TFR\secrets\discord_log_webhook.txt` - never commit it; the packager and ANDROID_RELEASE.md step
2b put it into builds. Release ledger (assets, versionCode, signer): `docs/tfr/releases.md`. Round history
before 2026-10-18: `docs/tfr/history/claude-md-rounds.md`.

## How the user wants me to work (supplied 2026-09-07)

**The TFR 1 main thread works through sub-threads** (the user, 2026-10-22: "Do all work in Sub-threads, and you should
auto select the correct level of effort needed, depending on the request"). In the user's main TFR 1 session, each
request's work runs in its own session (`start_session`, a self-contained brief) at an effort picked for the request -
high for balance, core rules, AI, engine merges and debugging; medium for UI, art, data, menus, save edits, docs and
packaging; low for lookups and log reads; xhigh only for a stubborn bug after one failed fix. The main thread
coordinates: it briefs, checks each result and reports to the user. A sub-thread does its own work and follows the rest
of this file.

Co-author, not an execution service. Their input is a **proposal to evaluate**, not an instruction
to obey - they are explicit that they are often "wrong, half-informed, or describing a solution
when I should be describing a problem".

**Before writing code** on anything non-trivial: restate the actual goal behind the request and say
so if the goal and the proposed method do not line up; state load-bearing assumptions and CHECK
them in the codebase rather than guessing; say what I do not know.

**Analyze before agreeing.** Give the unintended consequences (what else touches this, what breaks
downstream, the edge cases they did not mention - empty, first run, mid-game state). Say whether
their approach is the right one and lead with a better one if I have it, including its cost.
Correct a mistaken premise **at the top of the reply**, never after implementing on top of it.
Genuine agreement is one line and move on - but a conclusion, not a default.

**Opinions, not menus.** Multiple viable approaches get a pick and a reason. Volunteer
recommendations about things that actually matter. Disagree out loud and hold the position under
repetition; say what changed my mind if they convince me. No flattery openers.

**Mod-specific checks** whenever relevant: upstream merge burden (prefer a hook to a core edit -
see the merge-friction notes below); save/state compatibility; data formats confirmed against a
working example in the repo rather than inferred, because a wrong field name fails silently at
runtime; data over Java where possible; balance second-order effects; and **blast radius stated
before starting**, with a smaller first step proposed.

**Calibration**: a rename or one-line tweak just gets done. The full analysis is for behaviour
changes, shared systems, core/upstream edits, and anything hard to undo.

**Afterwards**: what changed and why for any judgment call they did not specify; what I
deliberately did NOT do; anything I could not verify and how to test it; and anything concerning
noticed outside the task, raised separately rather than silently fixed or silently ignored.

## Standing rules

**Commit locally; push only at a release (the user, 2026-09-20: "Let's not update the online repo until we are ready
to release").** Every round ends with a local commit; the push and the tag happen together at release time, when the
user calls the release.

**Docs travel in the same commit as the code.** Each round adds its entry to `MOD_CHANGELOG.md` (and to
`CORE_ENGINE_CHANGES.md` when it touches a stock engine file) and rewrites the NOW block above. Round narrative goes in
the changelog, never into this file; a release's block goes in `docs/tfr/releases.md`.

**Releases take the upstream engine update FIRST** (standing user preference, 2026-09-01), as its own round planned
ahead of the release: merge upstream -> reinstall BASE_INSTALL -> rebuild -> user re-tests -> then tag and publish. Why,
and what the merge clobbers: `docs/tfr/build-and-deploy.md`.

**Engine updates - conflicts go to the user (standing user rule, 2026-09-26: "If ever there is a real conflict when
updating to the latest Official game, please make me aware of it and the impact when merging.")** When a merge of
official Forge hits a REAL conflict - upstream changed or removed something our code depends on or also changed (a
behavior, a contract, a file both sides reworked), not whitespace or import noise - tell the user, as part of the merge
round and before it is packaged: each conflict (file and feature), what upstream changed, what we kept vs took, and the
impact on the game (what players will notice, saves, risk, what to test). Trivial textual conflicts get one line. If a
conflict forces a choice between upstream's behavior and ours, ask before choosing.

**Every piece of art also goes into the Art Library (standing user rule, 2026-10-16: "All art that we share, please make sure to put a copy also in the Art-Library repo we created. Make a rule of that please.")** Art made, imported, edited or shared in a round - sprites and atlas pages, icons, card faces, edited sprites (before and after), and the review / preview sheets sent to the user - gets a copy in the private Art Library repository (`C:\Users\User\Claude_Code\Bannerloard\Art-Library`, github.com/TheSAguy/Art-Library) in the same round, committed and pushed there: creature work in `Units/Custom/<Creature>` (with its Pixelate stills, clips and sheets), TFR's own art in `Projects/The Forsaken Realms/` (`Game Art/<kind>`, `Review Sheets`, `Key Art`, `Session Art`), and art the user hands over with its source. The game repository keeps the working originals; the library holds copies.

**World-map entrances carry a 1px black outline (standing user rule, 2026-10-16: "I like the 1px outline more ... Let's also make that the rule for entrances moving forward.")** Every cave, dungeon and side-boss place sprite has a 1px black outline round its silhouette, drawn inside its own atlas region (size, position and footprint unchanged). New or changed entrance art: run `python dev-tools/poi_outline.py --audit <repo>` - it lists entrances with no outline (exit 1); outline them with `--apply` (add them to dev-tools/entrance_outlines.json "outline"), or, when the score is in the 0.30-0.65 band and the sprite does carry a dark outline (a soft drop shadow lowers the score), add it to "reviewed_ok". Never edit an adventure/common page: copy the sprite onto the plane (`--apply` does that) and repoint its places.

**Importing from Realm of Legends or any other plane (standing user rule, 2026-10-16)**: no Commander, no quest lines
that are not ours, no promised gifts our game does not deliver, every reference resolves in our plane, and the import
is seen in the agent game before packaging; the round's changelog says it passed. Read the full checklist,
`docs/tfr/cross-plane-imports.md`, before the first edit of any import.

**Check the code graph before changing a shared method (standing user rule, 2026-10-18).** Before renaming, deleting or
changing the signature or behavior of a method or class that other code calls, list its callers from the local
graphify code graph and say in the round how many you found:
`C:/Users/User/.claude/Tools/graphify/venv/Scripts/python.exe C:/Users/User/.claude/Tools/graphify/gfy.py --repo tfr
affected Class.method --depth 2` (`explain Class` for a class). The `code-graph` skill has the details. It is not proof:
confirm with Grep before deleting anything; use Grep for text, config keys and data files (the graph covers code only).
`graphify-out/` is local (in `.git/info/exclude`) - never commit it, and never run `graphify install`,
`graphify claude install` or `graphify hook install`.

## Where to read

This is a fork of [Card-Forge/forge](https://github.com/Card-Forge/forge) (the open-source MTG engine) used to build
the Adventure-mode mod **"The Forsaken Realms"**. A prior session may have changed things this one does not remember,
so read in this order, and stop when you have what you need:

| Read | For |
|---|---|
| **this file (`CLAUDE.md`)** | standing rules, current state, ground rules, build summary |
| **`MOD_SCOPE.md`** | the feature list with live status per item. Start at the Currency line |
| **`MOD_CHANGELOG.md`** | the engineering log, ~40k lines, the source of truth for what is built. **Order**: rounds up to 121c run oldest-first; from round 122 on every new round is inserted NEWEST-FIRST right after 121c, so the newest round sits mid-file and the file's bottom is round 122. `grep -n "^## Round" MOD_CHANGELOG.md` lists them in file order; read the newest few, then grep by keyword |
| **`CORE_ENGINE_CHANGES.md`** | every stock-engine file this mod edits, for upstream-merge conflict work |
| **`ANDROID_RELEASE.md`** | the authoritative Android release procedure. Read before ANY Android work |
| **`docs/tfr/build-and-deploy.md`** | the deploy loop, the packager's guards, BASE_INSTALL, the release order in full |
| **`docs/tfr/cross-plane-imports.md`** | the full import checklist |
| **`docs/tfr/releases.md`** | the release ledger: assets, versionCode, signer, notes per release |
| **`docs/tfr/history/claude-md-rounds.md`** | frozen round history (rounds ~83-697) moved out of this file on 2026-10-18 |
| **`docs/design/`** | feature designs |

Then run `git log --oneline -15` and `git status` — those two tell you the rest.

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

## Build, package, deploy

Maven + JDK are installed portably on each machine (not tracked in git). Verify with
`mvn -pl forge-gui-mobile -am compile -DskipTests -o` (add `-o` once dependencies are already
cached locally) before considering a change done.

- **The user plays `C:\TFR\live\The Forsaken Realms\`.** Build the jar with `mvn -pl forge-gui-mobile-dev -am package
  -DskipTests`, then package with `python standalone-packaging/build_standalone.py` (`--zip` only for a release asset).
- **`E:\GAMES\Forge_2` is BASE_INSTALL**: the stock Forge the packager reads. Read-only for us - never write to it. An
  engine merge also needs the user to reinstall it at the matching daily, or the packager refuses to build.
- **Read `PACKAGE_OK.txt`** in the live folder before telling the user it is safe to play. Never substitute
  file-existence checks for reading this marker.
- **After a live package, refresh the agent game** (`C:\TFR\agent`, a mirror of live with its own profile) with
  `dev-tools\agent\agent_sync.cmd`, run from PowerShell; check the agent jar's timestamp, not the exit code. Test new
  features there on copies of the user's saves (the `tfr-play` skill), never in the user's own profile.
- **Packaging**, a packager refusal (jar freshness, game-running lock), `--full`, or the retired `E:\GAMES\FORGE`
  target: read `docs/tfr/build-and-deploy.md` first.
- Release order: build + update the live folder, **user tests it**, and only after their explicit pass does anything
  get pushed or published.

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
- **A Java COMMENT edited after `MVN EXIT 0` trips the packager's jar-freshness guard** (round 241) - rebuild the jar
  after any `.java` edit, comments included.
- **Another Claude session may be live on this same checkout.** On 2026-09-02 two sessions worked
  the tree at once; the tells were a `java.exe` running Maven that this session had not started, and
  doc edits appearing in `git status` unbidden. Before committing, merging or building, run
  `ListAgents`; if a peer session is listed, message it to stand down and wait for its build to
  exit. Never kill a build you did not start. With a peer on the checkout (the Procedural Pixel Creatures art
  session usually is), claim a round number and the write window by message before writing, commit by explicit
  pathspec only (never `git add -A`, never `git stash`), and run `git status` right before packaging - the packager
  ships whatever uncommitted files are in the tree.
- **`--zip` packaging always does the full stock-asset copy** and takes well over ten minutes. Do
  not give it a short timeout — killing it mid-run strips `PACKAGE_OK.txt` and leaves the live
  folder in the half-rebuilt state that marker exists to catch.
- **Edit saves with Java, never Python.** See `dev-tools/save-editing/README.md`.
- **Write the changelog entry in the SAME action as the code commit.** Rounds 73-76 and then round
  78 all shipped with detail only in commit messages and had to be backfilled. A thorough commit
  message is not a substitute and reads as done when it is not.
- **An "empty set means everything" convention must be read through its predicate at every site.**
  A raw `Set.contains()` near one is a latent bug — that pattern was a save-corrupting release
  blocker caught in round 78.
- **Any `Set.of` fed plane data needs a null guard** (round 194: one null quest tag in enemies.json threw in
  `loadMap()` and left the player stuck on "Autosaving").
- **Singleton state that must not outlive a save resets in `WorldStage.clearCache()`** - both a load and a new game
  reach it (`load()` calls it first, World generation calls it last).
