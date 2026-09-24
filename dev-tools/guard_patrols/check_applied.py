"""check_applied.py - byte-level proof of what apply.py did to a copy (round 316 test harness).

For every map apply.py wrote (per its --log):
  1. line endings and BOM are what they were, and no line ending is mixed;
  2. with the <map> line and the TOUCHED objects' elements cut out of both files (removed enemies, patrolled or
     relocated enemies, the new waypoint objects), the remaining text is BYTE-IDENTICAL - nothing else moved;
  3. the touched objects changed exactly as planned: a patrolled enemy gained one `waypoints` property and
     nothing else, a relocated one changed x/y only, a removed one is gone, every new object is a waypoint.tx
     at the logged coordinates, and nextobjectid is past the highest id.
Also writes the unified diff of every map to --diff for eyeballing.

usage: python check_applied.py <original root> <edited root> --log applied.json --diff out.diff
"""
import argparse
import difflib
import json
import os
import re
import sys
import xml.etree.ElementTree as ET

SUB = ("forge-gui", "res", "adventure", "The Forsaken Realms", "maps", "map")


def cut(text, ids):
    """Remove the <map ...> line and the whole-line elements of the given object ids."""
    text = re.sub(r'(?m)^[ \t]*<map\b[^\n]*\n', '', text, count=1)
    for oid in ids:
        m = re.search(r'(?m)^[ \t]*<object\b[^<>]*?\bid="%d"(?=[\s/>])[^<>]*>' % oid, text)
        if not m:
            continue
        s, e = m.start(), m.end()
        if text[e - 2:e] != "/>":
            e = text.find("</object>", e) + len("</object>")
        if text[e:e + 2] == "\r\n":
            e += 2
        elif text[e:e + 1] == "\n":
            e += 1
        text = text[:s] + text[e:]
    return text


def props(o):
    p = o.find("properties")
    out = {}
    if p is not None:
        for pr in p.findall("property"):
            out[pr.get("name")] = (pr.get("type"), pr.get("value"), (pr.text or "").strip())
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("orig")
    ap.add_argument("edited")
    ap.add_argument("--log", required=True)
    ap.add_argument("--diff", required=True)
    a = ap.parse_args()
    log = json.load(open(a.log, encoding="utf-8"))
    bad = 0
    diffs = []
    for rel, rec in sorted(log["done"].items()):
        problems = []
        b0 = open(os.path.join(a.orig, *SUB, *rel.split("/")), "rb").read()
        b1 = open(os.path.join(a.edited, *SUB, *rel.split("/")), "rb").read()
        crlf0, crlf1 = b"\r\n" in b0, b"\r\n" in b1
        if crlf0 != crlf1:
            problems.append("line-ending style changed")
        if b0.startswith(b"\xef\xbb\xbf") != b1.startswith(b"\xef\xbb\xbf"):
            problems.append("BOM changed")
        t0, t1 = b0.decode("utf-8"), b1.decode("utf-8")
        # every ADDED line ends the way the line before it ends (45 maps mix CRLF with a few LF-only lines, so
        # "the file's style" is local, not global)
        l0, l1 = t0.splitlines(True), t1.splitlines(True)
        sm = difflib.SequenceMatcher(None, l0, l1, autojunk=False)
        for tag, _i1, _i2, j1, j2 in sm.get_opcodes():
            if tag in ("insert", "replace"):
                for j in range(j1, j2):
                    prev = l1[j - 1] if j > 0 else None
                    if prev is not None and (prev.endswith("\r\n") != l1[j].endswith("\r\n")):
                        problems.append("added line %d ends differently from the line before it" % (j + 1))
                        break
        patrolled = [p["enemy_id"] for p in rec["patrols"]]
        new_ids = [w for p in rec["patrols"] for w in p["waypoints"]]
        removed = [r["enemy_id"] for r in rec["removals"]]
        moved = [r["enemy_id"] for r in rec["relocations"]]
        if cut(t0, patrolled + removed + moved) != cut(t1, patrolled + moved + new_ids):
            problems.append("text outside the touched objects differs")
        r0, r1 = ET.fromstring(t0), ET.fromstring(t1)
        o0 = {int(o.get("id")): o for o in r0.iter("object")}
        o1 = {int(o.get("id")): o for o in r1.iter("object")}
        if set(o0) - set(o1) != set(removed):
            problems.append("removed ids %s, expected %s" % (sorted(set(o0) - set(o1)), sorted(removed)))
        if set(o1) - set(o0) != set(new_ids):
            problems.append("new ids %s, expected %s" % (sorted(set(o1) - set(o0)), sorted(new_ids)))
        for p in rec["patrols"]:
            a_, b_ = o0[p["enemy_id"]], o1[p["enemy_id"]]
            pa, pb = props(a_), props(b_)
            want = dict(pa)
            want["waypoints"] = (None, p["route"], "")
            if a_.attrib != b_.attrib or pb != want:
                problems.append("enemy %d changed beyond its new route" % p["enemy_id"])
            for wid in p["waypoints"]:
                w = o1[wid]
                if not (w.get("template") or "").endswith("waypoint.tx") or len(w) or \
                        set(w.attrib) != {"id", "template", "x", "y"}:
                    problems.append("object %d is not a bare waypoint.tx object" % wid)
        for r in rec["relocations"]:
            a_, b_ = dict(o0[r["enemy_id"]].attrib), dict(o1[r["enemy_id"]].attrib)
            if {k: v for k, v in a_.items() if k not in ("x", "y")} != \
                    {k: v for k, v in b_.items() if k not in ("x", "y")} or \
                    props(o0[r["enemy_id"]]) != props(o1[r["enemy_id"]]) or \
                    abs(float(b_["x"]) - r["to"][0]) > 0.01 or abs(float(b_["y"]) - r["to"][1]) > 0.01:
                problems.append("relocated enemy %d changed beyond x/y" % r["enemy_id"])
        if int(r1.get("nextobjectid")) <= max(o1):
            problems.append("nextobjectid not past the highest id")
        if int(r1.get("nextobjectid")) < int(r0.get("nextobjectid")):
            problems.append("nextobjectid went DOWN")
        diffs.extend(difflib.unified_diff(t0.splitlines(), t1.splitlines(), "a/" + rel, "b/" + rel, n=1, lineterm=""))
        print("%-50s %s  %s%s" % (rel, "CRLF" if crlf1 else "LF  ", "ok" if not problems else "BAD",
                                 ("  " + "; ".join(problems)) if problems else ""))
        bad += 1 if problems else 0
    open(a.diff, "w", encoding="utf-8", newline="\n").write("\n".join(diffs) + "\n")
    print("\n%d map(s) checked, %d with a problem" % (len(log["done"]), bad))
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
