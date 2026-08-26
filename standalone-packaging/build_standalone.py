#!/usr/bin/env python3
"""Assemble "The Forsaken Realms" standalone game folder.

One command builds the shippable package (MOD_SCOPE.md #89 part 2):

    python standalone-packaging/build_standalone.py [--zip] [--full]

Inputs:
  - This repo (must be built first:
      mvn -pl forge-gui-mobile-dev -am package -DskipTests
    so forge-gui-mobile-dev/target/ holds the jar-with-dependencies).
  - A stock Forge install of the SAME engine version as the repo (BASE_INSTALL
    below) - used for the launcher exe/cmd shells and the installer-shaped res/
    tree (cardsfolder.zip etc.), which the repo does not contain in that shape.

Output: OUT_DIR/<GAME_NAME>/ - unzip-anywhere game folder. --zip also writes
OUT_DIR/<GAME_NAME>-<version>.zip.

What it does, in order:
  1. Verify the built jar's version matches BASE_INSTALL's jar version.
  2. Copy the include-listed root files + launcher shells from BASE_INSTALL
     (.exe/.cmd for Windows, .command for macOS double-click, .sh for a plain
     shell), renaming each to the game's name.
  3. Copy BASE_INSTALL/res EXCEPT res/adventure - SKIPPED on a fast-path run
     (see below), since this is by far the biggest, slowest step and its
     content is static between TFR-only rounds.
  4. res/adventure gets exactly two entries: common/ (from BASE_INSTALL, also
     skipped on a fast-path run) and the repo's "The Forsaken Realms" plane
     folder (ALWAYS rebuilt fresh - this is the part that actually changes).
  5. Overwrite the jar with the repo-built one (carries the mod engine code).
  6. Overlay the repo's non-adventure res edits (en-US.properties, skins art) -
     the list is DERIVED from git (diff vs the upstream merge base), so future
     rounds' res edits are picked up automatically.
  7. Drop in README.md, CREDITS.md, GAME_GUIDE.md; mirror LICENSE.txt +
     CREDITS.md into the plane folder ("licensing in the mod folder").
  8. Verify: our GameLauncher title marker is inside the shipped jar, the
     update-check kill is present, res/adventure has exactly 2 entries.

Fast path (2026-08-22): steps 3 and 4's common/ copy are skipped whenever the
existing output folder's res/.base_install_version marker already matches the
current BASE_INSTALL's jar name - that static content (tens of thousands of
files) only actually changes on an engine merge, not an ordinary TFR round.
A version mismatch (engine merge happened) forces the full copy automatically
even without --full. --zip release builds always do the full copy regardless
of the flag, since those are rare and matter more than local test iteration
speed. Pass --full to force it anyway (e.g. suspected local corruption).
"""
import argparse
import os
import re
import shutil
import subprocess
import sys
import time
import zipfile

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
BASE_INSTALL = r"E:\GAMES\Forge_2"
OUT_DIR = r"F:\FORGE\TFR-Standalone"
GAME_NAME = "The Forsaken Realms"
PLANE = "The Forsaken Realms"

# Root files copied verbatim from BASE_INSTALL (everything else is deliberately
# dropped: stock-Forge client, editors, uninstaller, upstream txt files that
# would mislead in a standalone context).
ROOT_INCLUDE = ["LICENSE.txt", "CONTRIBUTORS.txt", "build.txt"]


def fail(msg):
    print(f"ERROR: {msg}")
    sys.exit(1)


def find_jar(folder):
    jars = [f for f in os.listdir(folder)
            if re.fullmatch(r"forge-gui-mobile-dev-.*-jar-with-dependencies\.jar", f)]
    if len(jars) != 1:
        fail(f"expected exactly one mobile-dev jar in {folder}, found {jars}")
    return jars[0]


def git_overlay_list():
    """Non-adventure files under forge-gui/res that the mod changed vs upstream."""
    mb = subprocess.check_output(
        ["git", "merge-base", "HEAD", "upstream/master"], cwd=REPO, text=True).strip()
    out = subprocess.check_output(
        ["git", "-c", "core.quotepath=off", "diff", "--name-only", mb, "HEAD", "--", "forge-gui/res"],
        cwd=REPO, text=True, encoding="utf-8")
    files = [f for f in out.splitlines()
             if f and not f.startswith("forge-gui/res/adventure/")]
    return files


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--zip", action="store_true", help="also write a release zip")
    ap.add_argument("--full", action="store_true",
                     help="force a full rebuild of the static stock-asset tree (res minus "
                          "adventure, plus adventure/common) even if it looks current - use "
                          "after pointing BASE_INSTALL at a new engine version. A --zip release "
                          "build always does this automatically regardless of the flag.")
    args = ap.parse_args()

    jar_name = find_jar(os.path.join(BASE_INSTALL))
    built_jar_dir = os.path.join(REPO, "forge-gui-mobile-dev", "target")
    if not os.path.isdir(built_jar_dir):
        fail("repo not built - run: mvn -pl forge-gui-mobile-dev -am package -DskipTests")
    built_jar = os.path.join(built_jar_dir, find_jar(built_jar_dir))
    if os.path.basename(built_jar) != jar_name:
        fail(f"version mismatch: built {os.path.basename(built_jar)} vs base install {jar_name} - "
             "the launcher shells target the base install's jar name exactly")

    game_dir = os.path.join(OUT_DIR, GAME_NAME)
    # PACKAGE_OK.txt is the playability contract (2026-08-21 incident: the user launched the
    # game from this folder while a rebuild's slow rmtree was mid-deletion - stock splash, then
    # a hang as files vanished underneath it). Removed FIRST, written LAST: the folder is only
    # safe to run when the marker exists.
    ok_marker = os.path.join(game_dir, "PACKAGE_OK.txt")
    if os.path.exists(ok_marker):
        os.remove(ok_marker)

    # Fast-path (2026-08-22): everything under res/ EXCEPT adventure/<plane>, plus
    # adventure/common, comes straight from BASE_INSTALL and only actually changes when that
    # points at a new engine version (rare, deliberate) - not on an ordinary TFR-only round.
    # Recopying it (tens of thousands of files, the step this script itself calls "the big one")
    # on every local test build was pure waste. version_marker records which BASE_INSTALL jar
    # the static tree currently on disk was copied from; a mismatch (engine merge happened) or
    # a --zip release build always forces the full copy - --full is only needed to force-refresh
    # otherwise (e.g. suspected corruption).
    static_res_dir = os.path.join(game_dir, "res")
    version_marker = os.path.join(static_res_dir, ".base_install_version")
    have_current_static = (
        os.path.isdir(os.path.join(static_res_dir, "adventure", "common"))
        and os.path.exists(version_marker)
        and open(version_marker, encoding="utf-8").read().strip() == jar_name
    )
    full_rebuild = args.full or args.zip or not have_current_static

    if full_rebuild:
        if os.path.exists(game_dir):
            print(f"removing previous package at {game_dir}")
            shutil.rmtree(game_dir)
        # Windows: rmtree returns before the directory handle is fully released, so an
        # immediate makedirs can get WinError 5 - retry briefly.
        for attempt in range(30):
            try:
                os.makedirs(game_dir)
                break
            except (PermissionError, FileExistsError):
                if attempt == 29:
                    raise
                time.sleep(1)
    else:
        print("static asset tree matches the current base install - skipping the stock-res "
              "copy (pass --full to force it, or it happens automatically after an engine merge)")
        # Only clear what actually changes every round: the plane folder and any old jar(s).
        # res/<static>/ and res/adventure/common are left untouched.
        plane_dir = os.path.join(static_res_dir, "adventure", PLANE)
        if os.path.isdir(plane_dir):
            shutil.rmtree(plane_dir)
        for f in os.listdir(game_dir):
            if re.fullmatch(r"forge-gui-mobile-dev-.*-jar-with-dependencies\.jar", f):
                os.remove(os.path.join(game_dir, f))

    # 2. root files + launchers
    for f in ROOT_INCLUDE:
        shutil.copy2(os.path.join(BASE_INSTALL, f), game_dir)
    # the exe comes from OUR build (launch4j in forge-gui-mobile-dev's package phase) - it
    # carries the TFR icon from src/main/config/forge-adventure.ico, unlike the stock exe
    repo_exe = os.path.join(built_jar_dir, "forge-adventure.exe")
    if not os.path.exists(repo_exe):
        fail("forge-adventure.exe missing from target/ - run the package (not just compile) goal")
    shutil.copy2(repo_exe, os.path.join(game_dir, f"{GAME_NAME}.exe"))
    cmd = open(os.path.join(BASE_INSTALL, "forge-adventure.cmd"), encoding="utf-8",
               errors="ignore").read()
    open(os.path.join(game_dir, f"{GAME_NAME}.cmd"), "w", encoding="utf-8",
         newline="\r\n").write(cmd)
    # macOS/Linux launchers (2026-08-22 fix - a macOS user reported the .cmd "isn't booting,"
    # correctly: .cmd is a Windows batch file and can never run there. BASE_INSTALL already ships
    # working cross-platform launchers (forge-adventure.command for macOS double-click,
    # forge-adventure.sh for a plain shell) that this script simply never copied - Windows was the
    # only platform actually getting a launcher in the shipped zip. Same copy+rename pattern as
    # .cmd above; .command/.sh need the executable bit set explicitly (git/zip don't reliably
    # preserve it, and shutil.copy2 alone doesn't add it if the source lacks it either).
    for src_name, dst_suffix in ((".command", ".command"), (".sh", ".sh")):
        src = os.path.join(BASE_INSTALL, f"forge-adventure{src_name}")
        if not os.path.exists(src):
            fail(f"forge-adventure{src_name} missing from BASE_INSTALL - expected alongside forge-adventure.cmd")
        text = open(src, encoding="utf-8", errors="ignore").read()
        dst = os.path.join(game_dir, f"{GAME_NAME}{dst_suffix}")
        open(dst, "w", encoding="utf-8", newline="\n").write(text)
        os.chmod(dst, 0o755)

    adv = os.path.join(game_dir, "res", "adventure")
    if full_rebuild:
        # 3. res minus adventure
        print("copying res/ from base install (this is the big one)...")
        shutil.copytree(os.path.join(BASE_INSTALL, "res"), os.path.join(game_dir, "res"),
                        ignore=lambda d, names: ["adventure"] if os.path.samefile(d, os.path.join(BASE_INSTALL, "res")) else [])
        # 4a. adventure/common (static, part of the base install)
        os.makedirs(adv)
        shutil.copytree(os.path.join(BASE_INSTALL, "res", "adventure", "common"),
                        os.path.join(adv, "common"))
        with open(version_marker, "w", encoding="utf-8") as vm:
            vm.write(jar_name)
    # 4b. adventure/<plane> (changes every round - always fresh)
    print("copying the plane folder from the repo...")
    shutil.copytree(os.path.join(REPO, "forge-gui", "res", "adventure", PLANE),
                    os.path.join(adv, PLANE))

    # 5. our jar
    print("copying the built jar...")
    shutil.copy2(built_jar, os.path.join(game_dir, jar_name))

    # 6. non-adventure res overlay, derived from git
    # 2026-08-22 review fix: this list only ever ADDS/refreshes files - a fast-path run (which
    # skips steps 3/4's full stock-res recopy) had no way to undo a PREVIOUS round's overlay once
    # that round's repo edit got reverted, so the stale, already-reverted content stayed in
    # game_dir/res indefinitely on local test builds (release --zip builds were never affected -
    # those always force full_rebuild, which recopies res from BASE_INSTALL fresh regardless).
    # Fixed by persisting the overlay list itself; anything in last round's list but not this
    # round's gets its pristine BASE_INSTALL copy restored before the current list is (re)applied.
    overlay_manifest = os.path.join(game_dir, "res", ".overlay_manifest.txt")
    previous_overlay = []
    if os.path.exists(overlay_manifest):
        with open(overlay_manifest, encoding="utf-8") as mf:
            previous_overlay = [line.strip() for line in mf if line.strip()]

    overlay = git_overlay_list()
    for rel in previous_overlay:
        if rel in overlay:
            continue
        base_src = os.path.join(BASE_INSTALL, "res", os.path.relpath(rel, "forge-gui/res"))
        dst = os.path.join(game_dir, "res", os.path.relpath(rel, "forge-gui/res"))
        if os.path.exists(base_src):
            shutil.copy2(base_src, dst)
            print(f"  overlay reverted (no longer in repo diff): {rel}")

    for rel in overlay:
        src = os.path.join(REPO, rel)
        dst = os.path.join(game_dir, "res", os.path.relpath(rel, "forge-gui/res"))
        if not os.path.exists(src):
            print(f"  overlay skip (deleted in repo): {rel}")
            continue
        os.makedirs(os.path.dirname(dst), exist_ok=True)
        shutil.copy2(src, dst)
    print(f"overlaid {len(overlay)} repo res file(s): {overlay}")
    with open(overlay_manifest, "w", encoding="utf-8") as mf:
        mf.write("\n".join(overlay))

    # 7. docs
    here = os.path.dirname(os.path.abspath(__file__))
    shutil.copy2(os.path.join(here, "README.md"), game_dir)
    shutil.copy2(os.path.join(here, "CREDITS.md"), game_dir)
    guide = os.path.join(adv, PLANE, "GUIDE.md")
    if os.path.exists(guide):
        shutil.copy2(guide, os.path.join(game_dir, "GAME_GUIDE.md"))
    shutil.copy2(os.path.join(BASE_INSTALL, "LICENSE.txt"), os.path.join(adv, PLANE))
    shutil.copy2(os.path.join(here, "CREDITS.md"), os.path.join(adv, PLANE))

    # 8. verify
    errors = []
    with zipfile.ZipFile(os.path.join(game_dir, jar_name)) as z:
        gl = z.read("forge/app/GameLauncher.class")
        if b"The Forsaken Realms (Forge " not in gl:
            errors.append("shipped jar's GameLauncher lacks the standalone title - wrong/stale jar?")
        pp = z.read("forge/localinstance/properties/ForgeProfileProperties.class")
        if b"ForsakenRealms" not in pp:
            errors.append("shipped jar lacks the ForsakenRealms data-dir rebrand")
    entries = sorted(os.listdir(adv))
    if entries != sorted(["common", PLANE]):
        errors.append(f"res/adventure should hold exactly common + the plane, has: {entries}")
    if errors:
        for e in errors:
            print("VERIFY FAIL:", e)
        sys.exit(1)

    total = sum(os.path.getsize(os.path.join(r, f))
                for r, _, fs in os.walk(game_dir) for f in fs)
    with open(ok_marker, "w", encoding="utf-8") as mk:
        mk.write("Package verified complete. Safe to play from this folder.\n")
    print(f"\nPackage OK: {game_dir}  ({total / 1024 / 1024:.0f} MB)")

    if args.zip:
        # Name the release zip by the GAME's version (config.json modVersion), not the Forge
        # engine version - players downloading "v1.00" were confused by a "2.0.15" filename.
        version = "dev"
        try:
            cfg = open(os.path.join(REPO, "forge-gui", "res", "adventure", PLANE, "config.json"),
                       encoding="utf-8").read()
            mv = re.search(r'"modVersion"\s*:\s*"([^"]+)"', cfg)
            if mv:
                version = "v" + mv.group(1)
        except OSError:
            pass
        zpath = os.path.join(OUT_DIR, f"{GAME_NAME.replace(' ', '-')}-{version}.zip")
        print(f"zipping to {zpath} ...")
        with zipfile.ZipFile(zpath, "w", zipfile.ZIP_DEFLATED) as z:
            for r, _, fs in os.walk(game_dir):
                for f in fs:
                    p = os.path.join(r, f)
                    arcname = os.path.relpath(p, OUT_DIR)
                    if f.endswith((".command", ".sh")):
                        # 2026-08-22 review fix: this script only ever runs on the Windows dev box
                        # (BASE_INSTALL/OUT_DIR above), so os.chmod(0o755) on these two launchers
                        # is a no-op on NTFS, and a plain z.write() derives its zip entry's Unix
                        # permission bits from that same no-exec Windows stat - the +x bit never
                        # makes it into the shipped zip either way. create_system=3 (Unix) tells
                        # extractors to honor external_attr as a Unix mode; without it macOS/Linux
                        # unzip tools ignore the permission word entirely regardless of its value.
                        zi = zipfile.ZipInfo.from_file(p, arcname)
                        zi.compress_type = zipfile.ZIP_DEFLATED
                        zi.create_system = 3
                        zi.external_attr = 0o100755 << 16  # regular file, rwxr-xr-x
                        with open(p, "rb") as fh:
                            z.writestr(zi, fh.read())
                    else:
                        z.write(p, arcname)
        print("zip done")


if __name__ == "__main__":
    main()
