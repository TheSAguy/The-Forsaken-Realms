# Build, package, deploy, and the release order

The full reference behind `CLAUDE.md`'s "Build, package, deploy" summary. Read it before packaging, before a
release, and before debugging a packager refusal. Android: `ANDROID_RELEASE.md`.

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

## Deploy (live game folder at `C:\TFR\live\The Forsaken Realms\`)

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
   `C:\TFR\live\The Forsaken Realms\` from `BASE_INSTALL` (stock engine shell) + the
   repo-built jar + `forge-gui/res/adventure/The Forsaken Realms/` + a git-derived overlay of the
   mod's non-adventure `res` edits (so future rounds' res edits are picked up automatically).
   The plane folder is **always** rebuilt fresh, so a changed resource needs no separate copy step -
   and unlike the old `cp -r` mirror, files deleted from the repo really do disappear from the live
   folder.
   - `--zip` **only** when building a release asset; it also forces the full stock-asset copy. No
     local zips are kept - upload it, then delete it from `C:\TFR\live\`.
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
