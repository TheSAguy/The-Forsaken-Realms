r"""test_import316.py - run import316.py against a COPY of the repo (never the repo) and every check the round-179 import
needed, with a pristine baseline first so each result can be told apart from what was already there.

Fake root (enemy_import\fakeroot), same relative layout as C:\TFR\repo:
    forge-gui\res\adventure\The Forsaken Realms   the plane, copied from the repo's working tree as it is now
    forge-gui\res\adventure\common                the shared assets (sprites, tilesets) the tools resolve against
    forge-gui\res\editions                        for deckcard_loot_coverage.py
    standalone-packaging\CREDITS.md
    dev-tools\enemy_scale.py, sprite_sizes.py     copies - they find the repo from their own location
    dev-tools\gen_caves.py                        a copy with REPO pointed at the fake root (the original hardcodes
                                                  C:\TFR\repo and writes its manifest next to itself)
    dev-tools\deckcard_loot_coverage.py           a copy (repo-relative like enemy_scale)
The repo's validate_plane_data.py, arena_champion_audit.py and sprite_artifact_audit.py take a path and are run in
place (read-only). Outputs: qa\test_*.txt.
Steps: 0 the inputs' own QA; 1 baseline on the untouched copy; 2 import316 (no root / dry / real / again);
3 enemy_scale --write + a dry run; 4 validate; 5 arena champions, loot coverage, stray-pixel audit; 6 the change
set (fake plane vs repo); 7 the gen_caves hazard, shown on the imported copy and then undone.
usage: python test_import316.py"""
import filecmp, os, re, shutil, subprocess, sys, time

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = r"C:\TFR\repo"
FAKE = os.path.join(HERE, "fakeroot")
QA = os.path.join(HERE, "qa")
REL_PLANE = os.path.join("forge-gui", "res", "adventure", "The Forsaken Realms")
REL_COMMON = os.path.join("forge-gui", "res", "adventure", "common")
PY = [sys.executable, "-B"]
CAVES = os.path.join(REL_PLANE, "maps", "map", "cave")


def run(cmd, out_name, cwd=None, ok=(0,)):
    t = time.time()
    env = dict(os.environ, PYTHONIOENCODING="utf-8")
    p = subprocess.run(cmd, cwd=cwd, capture_output=True, text=True, encoding="utf-8", errors="replace", env=env)
    text = p.stdout + (("\n[stderr]\n" + p.stderr) if p.stderr.strip() else "")
    open(os.path.join(QA, out_name), "w", encoding="utf-8").write(text)
    print("  %-40s exit %d  %5.1fs  -> qa\\%s" % (" ".join(os.path.basename(c) for c in cmd[2:4])[:40], p.returncode,
                                                  time.time() - t, out_name))
    if p.returncode not in ok:
        print(text[-3000:])
        raise SystemExit("step failed: %s" % cmd)
    return text


def tree_diff(a, b):
    """Files that differ / exist only in b / only in a, relative paths."""
    changed, added, removed = [], [], []
    for root, dirs, files in os.walk(b):
        for f in files:
            pb = os.path.join(root, f)
            rel = os.path.relpath(pb, b)
            pa = os.path.join(a, rel)
            if not os.path.exists(pa):
                added.append(rel)
            elif not filecmp.cmp(pa, pb, shallow=False):
                changed.append(rel)
    for root, dirs, files in os.walk(a):
        for f in files:
            rel = os.path.relpath(os.path.join(root, f), a)
            if not os.path.exists(os.path.join(b, rel)):
                removed.append(rel)
    return sorted(changed), sorted(added), sorted(removed)


def make_fake():
    if os.path.isdir(FAKE):
        shutil.rmtree(FAKE)
    t = time.time()
    shutil.copytree(os.path.join(REPO, REL_PLANE), os.path.join(FAKE, REL_PLANE))
    shutil.copytree(os.path.join(REPO, REL_COMMON), os.path.join(FAKE, REL_COMMON))
    shutil.copytree(os.path.join(REPO, "forge-gui", "res", "editions"), os.path.join(FAKE, "forge-gui", "res", "editions"))
    os.makedirs(os.path.join(FAKE, "standalone-packaging"))
    shutil.copy2(os.path.join(REPO, "standalone-packaging", "CREDITS.md"), os.path.join(FAKE, "standalone-packaging"))
    dt = os.path.join(FAKE, "dev-tools")
    os.makedirs(dt)
    for f in ("enemy_scale.py", "sprite_sizes.py", "deckcard_loot_coverage.py"):
        shutil.copy2(os.path.join(REPO, "dev-tools", f), dt)
    src = open(os.path.join(REPO, "dev-tools", "gen_caves.py"), encoding="utf-8").read()
    assert src.count('REPO = r"C:\\TFR\\repo"') == 1
    src = src.replace('REPO = r"C:\\TFR\\repo"', 'REPO = r"%s"' % FAKE)
    open(os.path.join(dt, "gen_caves.py"), "w", encoding="utf-8", newline="\n").write(src)
    print("fake root copied in %.0fs: %s" % (time.time() - t, FAKE))


def restore_caves():
    shutil.rmtree(os.path.join(FAKE, CAVES))
    shutil.copytree(os.path.join(REPO, CAVES), os.path.join(FAKE, CAVES))


def manifest_enemies(path):
    out = {}
    for l in open(path, encoding="utf-8"):
        m = re.match(r"^(\S+)\s+(\d+)\s+.*enemies: (.*?) \| loot", l)
        if m:
            out[(m.group(1), m.group(2))] = [x.strip() for x in m.group(3).split(";")]
    return out


def main():
    os.makedirs(QA, exist_ok=True)
    sys.path.insert(0, HERE)
    from roster316 import ROSTER
    new_names = {r[1] for r in ROSTER}

    print("0. the inputs' own QA")
    run(PY + [os.path.join(HERE, "tools", "qa_atlases.py")], "test_qa_atlases.txt")
    run(PY + [os.path.join(HERE, "deck_audit316.py")], "test_deck_audit.txt")
    run(PY + [os.path.join(HERE, "tools", "check_decks.py")], "test_check_decks.txt")

    make_fake()
    plane = os.path.join(FAKE, REL_PLANE)
    validate = PY + [os.path.join(REPO, "dev-tools", "validate_plane_data.py"), plane]
    stage = os.path.join(REPO, "dev-tools", "validate_plane_data_stage_fields.txt")

    print("1. baseline on the untouched copy")
    run(validate + [os.path.join(QA, "test_validate_before_report.txt"), stage], "test_validate_before.txt", ok=(0, 1))
    run(PY + [os.path.join(FAKE, "dev-tools", "enemy_scale.py")], "test_enemy_scale_before.txt", cwd=FAKE)
    run(PY + [os.path.join(REPO, "dev-tools", "arena_champion_audit.py"), FAKE], "test_arena_audit_before.txt")
    run(PY + [os.path.join(FAKE, "dev-tools", "gen_caves.py"), "--no-register"], "test_gen_caves_before.txt", cwd=FAKE)
    ch, ad, rm = tree_diff(os.path.join(REPO, CAVES), os.path.join(FAKE, CAVES))
    open(os.path.join(QA, "test_gen_caves_control.txt"), "w").write(
        "gen_caves.py --no-register on the UNTOUCHED copy vs the repo's caves:\nchanged %d %s\nadded %d %s\nremoved %d %s\n"
        % (len(ch), ch, len(ad), ad, len(rm), rm))
    print("  control: gen_caves on the UNTOUCHED copy rewrites %d of the cave files" % len(ch))
    shutil.copy2(os.path.join(FAKE, "dev-tools", "gen_caves_manifest.txt"), os.path.join(QA, "test_gen_caves_manifest_before.txt"))
    restore_caves()

    print("2. import316.py against the copy")
    run(PY + [os.path.join(HERE, "import316.py")], "test_import_noarg.txt", ok=(1,))
    run(PY + [os.path.join(HERE, "import316.py"), FAKE, "--dry"], "test_import_dry.txt")
    run(PY + [os.path.join(HERE, "import316.py"), FAKE], "test_import.txt")
    again = run(PY + [os.path.join(HERE, "import316.py"), FAKE], "test_import_again.txt")
    assert "nothing to do" in again, "second run was not a no-op"

    print("3. enemy_scale.py --write (the copy), then a dry run that must change nothing")
    run(PY + [os.path.join(FAKE, "dev-tools", "enemy_scale.py"), "--write"], "test_enemy_scale_write.txt", cwd=FAKE)
    after = run(PY + [os.path.join(FAKE, "dev-tools", "enemy_scale.py")], "test_enemy_scale_after.txt", cwd=FAKE)
    assert "scales changed: 0 " in after

    print("4. validate_plane_data.py on the copy")
    run(validate + [os.path.join(QA, "test_validate_after_report.txt"), stage], "test_validate_after.txt", ok=(0, 1))
    b = open(os.path.join(QA, "test_validate_before_report.txt"), encoding="utf-8").read().splitlines()[3:]
    a = open(os.path.join(QA, "test_validate_after_report.txt"), encoding="utf-8").read().splitlines()[3:]
    print("  validator report lines after the header: before %d, after %d, identical: %s" % (len(b), len(a), a == b))

    print("5. arena champions, deck-card loot coverage, stray-pixel audit")
    run(PY + [os.path.join(REPO, "dev-tools", "arena_champion_audit.py"), FAKE], "test_arena_audit_after.txt")
    run(PY + [os.path.join(FAKE, "dev-tools", "deckcard_loot_coverage.py"), "--worst", "5000"], "test_loot_coverage.txt", cwd=FAKE)
    run(PY + [os.path.join(REPO, "dev-tools", "sprite_artifact_audit.py"), "--root", FAKE, "--only", "tfr"],
        "test_sprite_artifact_audit.txt", ok=(0, 1))

    print("6. the change set: fake plane vs repo (import316 + enemy_scale --write)")
    ch, ad, rm = tree_diff(os.path.join(REPO, REL_PLANE), plane)
    credits = "CREDITS.md" if not filecmp.cmp(os.path.join(REPO, "standalone-packaging", "CREDITS.md"),
                                              os.path.join(FAKE, "standalone-packaging", "CREDITS.md"), shallow=False) else "-"
    c2, a2, r2 = [credits], [], []
    with open(os.path.join(QA, "test_plane_diff.txt"), "w") as fh:
        fh.write("CHANGED %d\n%s\n\nADDED %d\n%s\n\nREMOVED %d\n%s\n\nstandalone-packaging changed: %s\n" % (
            len(ch), "\n".join(ch), len(ad), "\n".join(ad), len(rm), "\n".join(rm), c2 + a2 + r2))
    print("  plane: %d changed, %d added, %d removed; standalone-packaging: %s" % (len(ch), len(ad), len(rm), c2 + a2 + r2))
    for c in ch:
        print("    changed: " + c)

    print("7. the hazard: gen_caves.py --no-register on the IMPORTED copy (then undone)")
    run(PY + [os.path.join(FAKE, "dev-tools", "gen_caves.py"), "--no-register"], "test_gen_caves_after.txt", cwd=FAKE)
    shutil.copy2(os.path.join(FAKE, "dev-tools", "gen_caves_manifest.txt"), os.path.join(QA, "test_gen_caves_manifest_after.txt"))
    ch, _, _ = tree_diff(os.path.join(REPO, CAVES), os.path.join(FAKE, CAVES))
    mb = manifest_enemies(os.path.join(QA, "test_gen_caves_manifest_before.txt"))
    ma = manifest_enemies(os.path.join(QA, "test_gen_caves_manifest_after.txt"))
    placed = [(k, e) for k in sorted(ma) for e in ma[k] if re.sub(r" \(.*", "", e) in new_names]
    with open(os.path.join(QA, "test_gen_caves_hazard.txt"), "w") as fh:
        fh.write("gen_caves.py --no-register after the import rewrites %d of the 78 caves (the untouched plane: see "
                 "test_gen_caves_control.txt); roamer lists that differ from a regeneration WITHOUT the import: %d;\n"
                 "new enemies it would place: %d\n%s\n" % (len(ch), sum(1 for k in ma if ma[k] != mb.get(k)),
                                                            len(placed), "\n".join("  %s %s: %s" % (k[0], k[1], e) for k, e in placed)))
    print("  would rewrite %d caves, re-roll %d roamer lists, place %d new-enemy spots - undone" % (
        len(ch), sum(1 for k in ma if ma[k] != mb.get(k)), len(placed)))
    restore_caves()


if __name__ == "__main__":
    main()
