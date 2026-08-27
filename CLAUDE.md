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
- `origin` is the user's own fork (`TheSAguy/mtg-forge-mod`); `upstream` is the original
  `Card-Forge/forge` project, for pulling in engine updates only - never push mod work there.
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

## Deploy (installed game at `E:\GAMES\FORGE`)

**The installed game folder has THREE separate jars — splicing the wrong one silently ships
nothing.** Confirmed 2026-08-11 (round 5) the hard way: two full rounds of work (Deck Tester,
resource-icon/difficulty-pricing) got spliced into `forge-gui-desktop-...jar` and never reached
the player, because Adventure mode doesn't launch from that jar.

- **`forge-gui-mobile-dev-2.0.14-SNAPSHOT-jar-with-dependencies.jar`** — what `forge-adventure.exe`/
  `.cmd`/`.sh` actually run (confirmed by reading `forge-adventure.cmd`'s own `-jar` argument, not
  assumed from the filename). **This is the one that matters for every change under
  `forge/adventure/`.**
- **`forge-gui-desktop-2.0.14-SNAPSHOT-jar-with-dependencies.jar`** — what plain `forge.exe`/`.cmd`
  runs (the regular non-Adventure client). Splice it too for consistency, but it is NOT what the
  user tests Adventure-mode changes against.
- `adventure-editor-jar-with-dependencies.jar` / `gdx-particle-editor.jar` — unrelated tools, never
  need splicing for mod-code changes.

Deploy loop, in order:
1. `mvn -pl forge-gui-mobile -am compile -DskipTests -o -q` (fix any errors/checkstyle first)
2. From `forge-gui-mobile/target/classes`: `jar uf "<jar>" forge/adventure` — splice into
   **both** `forge-gui-mobile-dev-...jar` and `forge-gui-desktop-...jar` at
   `E:\GAMES\FORGE\`, mobile-dev first since it's the one that actually matters
3. If any file under `forge-gui/res/adventure/The Forsaken Realms/` changed, mirror the whole
   folder on top of `E:\GAMES\FORGE\res\adventure\The Forsaken Realms\` (`cp -r`, plus explicit
   `rm` for anything deleted from the repo - `cp -r` never removes stale destination files)
4. **Spot-check the splice actually landed** before telling the user it's ready: extract the
   changed `.class` file(s) from the jar just spliced and `grep` for a string literal unique to
   this round's edit (e.g. `jar xf <jar> forge/adventure/util/EconomyBuildings.class` into a temp
   dir, then `grep -a -o "<new string>" EconomyBuildings.class`). A clean `mvn compile` only proves
   the source compiles - it proves nothing about which jar actually received the splice.
