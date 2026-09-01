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
