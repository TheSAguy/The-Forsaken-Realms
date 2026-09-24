"""check_bytes.py - byte-level proof of what apply.py did to a copy (round 329).

For every map in the apply log: BOM and line endings as before (every line keeps its own ending; an added line
ends like the line before it), and every changed line is one of: the <map> line (nextobjectid only), a moved
waypoint's line (x/y only), an enemy's waypoints property line (value changed or line removed), or an added
waypoint object line. Unchanged maps are compared too: identical bytes.

usage: python check_bytes.py <original root> <edited root> --log applied.json [--diff out.diff]
"""
import argparse
import difflib
import json
import os
import re
import sys

SUB = ("forge-gui", "res", "adventure", "The Forsaken Realms", "maps", "map")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("orig")
    ap.add_argument("edited")
    ap.add_argument("--log", required=True)
    ap.add_argument("--diff")
    a = ap.parse_args()
    log = json.load(open(a.log, encoding="utf-8"))
    r0 = os.path.join(a.orig, *SUB)
    r1 = os.path.join(a.edited, *SUB)
    bad = 0
    diffs = []
    kinds = {"map": 0, "move": 0, "prop": 0, "drop": 0, "add": 0}
    for rel, rec in sorted(log["done"].items()):
        b0 = open(os.path.join(r0, *rel.split("/")), "rb").read()
        b1 = open(os.path.join(r1, *rel.split("/")), "rb").read()
        probs = []
        if b0.startswith(b"\xef\xbb\xbf") != b1.startswith(b"\xef\xbb\xbf"):
            probs.append("BOM changed")
        l0 = b0.decode("utf-8").splitlines(True)
        l1 = b1.decode("utf-8").splitlines(True)
        moved = {m["id"] for m in rec["moved"]}
        added = {x["id"] for x in rec["added"]}
        sm = difflib.SequenceMatcher(None, l0, l1, autojunk=False)
        for tag, i1, i2, j1, j2 in sm.get_opcodes():
            if tag == "equal":
                continue
            old, new = l0[i1:i2], l1[j1:j2]
            if tag == "replace" and len(old) != len(new):
                # adjacent moves + insertions merged by difflib: peel the added waypoint lines off the end
                ins = []
                while new and len(new) > len(old):
                    m = re.search(r'<object id="(\d+)" template="[^"]*waypoint\.tx"', new[-1])
                    if not m or int(m.group(1)) not in added:
                        break
                    ins.insert(0, new.pop())
                for k, y in enumerate(ins):
                    prev = new[-1] if k == 0 and new else (ins[k - 1] if k else l1[j1 - 1])
                    if prev.endswith("\r\n") != y.endswith("\r\n"):
                        probs.append("added line's ending differs from the line before it")
                    kinds["add"] += 1
            if tag == "replace" and len(old) == len(new):
                for x, y in zip(old, new):
                    if x.endswith("\r\n") != y.endswith("\r\n"):
                        probs.append("line ending changed: %r" % y[:60])
                    if x.lstrip().startswith("<map "):
                        if re.sub(r'nextobjectid="\d+"', "", x) != re.sub(r'nextobjectid="\d+"', "", y):
                            probs.append("<map> line changed beyond nextobjectid")
                        kinds["map"] += 1
                        continue
                    m = re.search(r'<object id="(\d+)"', x)
                    if m and int(m.group(1)) in moved:
                        strip = lambda s: re.sub(r'\s[xy]="[^"]*"', "", s)
                        if strip(x) != strip(y):
                            probs.append("waypoint %s line changed beyond x/y" % m.group(1))
                        kinds["move"] += 1
                        continue
                    if 'name="waypoints"' in x and 'name="waypoints"' in y:
                        if re.sub(r'value="[^"]*"', "", x) != re.sub(r'value="[^"]*"', "", y):
                            probs.append("route line changed beyond its value")
                        kinds["prop"] += 1
                        continue
                    probs.append("unexpected change: %r -> %r" % (x[:80], y[:80]))
            elif tag == "delete":
                for x in old:
                    if 'name="waypoints"' in x:
                        kinds["drop"] += 1
                    else:
                        probs.append("unexpected removal: %r" % x[:80])
            elif tag == "insert":
                for k, y in enumerate(new):
                    m = re.search(r'<object id="(\d+)" template="[^"]*waypoint\.tx" x="[-0-9.]+" y="[-0-9.]+"/>', y)
                    if not m or int(m.group(1)) not in added:
                        probs.append("unexpected insertion: %r" % y[:80])
                        continue
                    prev = l1[j1 + k - 1]
                    if prev.endswith("\r\n") != y.endswith("\r\n"):
                        probs.append("added line's ending differs from the line before it")
                    kinds["add"] += 1
            else:
                probs.append("unexpected %s block: %r -> %r" % (tag, old[:2], new[:2]))
        if probs:
            bad += 1
            print("PROBLEM %s: %s" % (rel, "; ".join(probs[:5])))
        if a.diff:
            diffs.extend(difflib.unified_diff([s.rstrip("\r\n") + "\n" for s in l0], [s.rstrip("\r\n") + "\n" for s in l1],
                                              "a/" + rel, "b/" + rel, n=0))
    # everything else untouched
    other = 0
    for d, _s, files in os.walk(r0):
        for f in files:
            if not f.endswith(".tmx"):
                continue
            rel = os.path.relpath(os.path.join(d, f), r0).replace("\\", "/")
            if rel in log["done"]:
                continue
            if open(os.path.join(r0, *rel.split("/")), "rb").read() != open(os.path.join(r1, *rel.split("/")), "rb").read():
                print("PROBLEM %s changed but is not in the log" % rel)
                bad += 1
            other += 1
    if a.diff:
        open(a.diff, "w", encoding="utf-8").writelines(diffs)
    print("%d edited map(s) checked (%s), %d untouched map(s) byte-identical; %d with problems"
          % (len(log["done"]), ", ".join("%s %d" % kv for kv in kinds.items()), other, bad))
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
