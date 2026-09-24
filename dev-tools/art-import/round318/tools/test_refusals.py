r"""test_refusals.py - import316.py's refusal paths, each on a fresh copy of the plane (never the repo), and proof that a
refused run writes NOTHING (every file hashed before and after):
  A  some but not all of the 43 names already in enemies.json            -> REFUSED (partial import)
  B  a target deck already there with different content                  -> REFUSED (file differs)
  C  enemies.json reformatted so it no longer round-trips                -> REFUSED (edit by hand)
  D  a re-skin target missing from enemies.json                          -> REFUSED
  E  --src pointing at a copy of the inputs                              -> imports like the default
usage: python tools/test_refusals.py"""
import hashlib, json, os, shutil, subprocess, sys

HERE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
REPO = r"C:\TFR\repo"
REL_PLANE = os.path.join("forge-gui", "res", "adventure", "The Forsaken Realms")
ROOT = os.path.join(HERE, "fakeroot_refusals")


def fresh():
    if os.path.isdir(ROOT):
        shutil.rmtree(ROOT)
    shutil.copytree(os.path.join(REPO, REL_PLANE), os.path.join(ROOT, REL_PLANE))
    os.makedirs(os.path.join(ROOT, "standalone-packaging"))
    shutil.copy2(os.path.join(REPO, "standalone-packaging", "CREDITS.md"), os.path.join(ROOT, "standalone-packaging"))
    return os.path.join(ROOT, REL_PLANE)


def snapshot():
    out = {}
    for dp, _, fs in os.walk(ROOT):
        for f in fs:
            p = os.path.join(dp, f)
            out[p] = hashlib.md5(open(p, "rb").read()).hexdigest()
    return out


def run(*extra):
    p = subprocess.run([sys.executable, "-B", os.path.join(HERE, "import316.py"), ROOT] + list(extra),
                       capture_output=True, text=True, encoding="utf-8", errors="replace")
    return p.returncode, (p.stdout + p.stderr).strip()


def expect_refusal(label, needle):
    before = snapshot()
    code, out = run()
    after = snapshot()
    ok = code != 0 and needle in out and before == after
    print("%-58s %s  (exit %d, files unchanged: %s)\n      %s" % (label, "PASS" if ok else "FAIL", code, before == after,
                                                                 out.splitlines()[-1][:150]))
    return ok


def main():
    results = []
    ej = lambda plane: os.path.join(plane, "world", "enemies.json")

    plane = fresh()                                                     # A: a partial import
    sys.path.insert(0, HERE)
    import import316
    from roster316 import ROSTER
    E = json.load(open(ej(plane), encoding="utf-8"))
    _, name, rank, colors, theme, extra = ROSTER[0]
    E.append(import316.entry(name, rank, colors, theme, extra, ["blue"]))
    open(ej(plane), "wb").write(json.dumps(E, indent=4, ensure_ascii=False).encode("utf-8") + b"\n")
    results.append(expect_refusal("A partial import (1 of 43 names present)", "REFUSED"))

    plane = fresh()                                                     # B: a conflicting target file
    d = os.path.join(plane, "decks", "standard", "tfr", ROSTER[5][0] + ".dck")
    open(d, "w", encoding="utf-8", newline="\n").write("[metadata]\nName=%s\n[Main]\n60 Plains\n" % ROSTER[5][1])
    results.append(expect_refusal("B target deck exists with different content", "exists with different content"))

    plane = fresh()                                                     # C: enemies.json does not round-trip
    raw = open(ej(plane), "rb").read()
    open(ej(plane), "wb").write(raw.replace(b"\n", b"\r\n", 3))
    results.append(expect_refusal("C enemies.json no longer round-trips", "round-trip"))

    plane = fresh()                                                     # D: a re-skin target missing
    E = [e for e in json.load(open(ej(plane), encoding="utf-8")) if e["name"] != "Keiga"]
    open(ej(plane), "wb").write(json.dumps(E, indent=4, ensure_ascii=False).encode("utf-8") + b"\n")
    results.append(expect_refusal("D re-skin target Keiga missing", "Keiga"))

    plane = fresh()                                                     # E: --src
    src = os.path.join(ROOT, "_staging")
    shutil.copytree(os.path.join(HERE, "atlases"), os.path.join(src, "atlases"))
    shutil.copytree(os.path.join(HERE, "decks"), os.path.join(src, "decks"))
    code, out = run("--src", src)
    ok = code == 0 and "enemies.json: +43 entries" in out and "written: 152 file(s)" in out
    code2, out2 = run("--src", src)
    ok = ok and code2 == 0 and "nothing to do" in out2
    print("%-58s %s  (exit %d, then %d: %s)" % ("E --src <copy of the inputs> imports, then no-ops", "PASS" if ok else "FAIL",
                                                 code, code2, out2.splitlines()[-1]))
    results.append(ok)

    shutil.rmtree(ROOT)
    print("\n%d of %d refusal/option tests passed" % (sum(results), len(results)))
    return 0 if all(results) else 1


if __name__ == "__main__":
    sys.exit(main())
