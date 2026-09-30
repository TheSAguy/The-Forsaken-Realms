"""enemy_history.py - which commit ADDED each enemy object in every TFR map (read-only; git show only).

Writes enemy_history.json: {rel_map: {id: {"added": sha, "added_msg": subject, "changes": [[sha, subject, what]]}}}
covering every enemy object present at HEAD. An enemy present in the plane's first commit
(3180f4aa6b7, the rename that created the folder) is recorded with added = "BASE".
"""
import json
import os
import re
import subprocess
import sys
import xml.etree.ElementTree as ET

REPO = r"C:\TFR\repo"
PREFIX = "forge-gui/res/adventure/The Forsaken Realms/maps/map/"
HERE = os.path.dirname(os.path.abspath(__file__))


def git(*args):
    return subprocess.run(["git", "-C", REPO] + list(args), capture_output=True, check=False).stdout


def objects_at(sha, path):
    raw = git("show", "%s:%s" % (sha, path))
    if not raw:
        return None
    try:
        root = ET.fromstring(raw)
    except ET.ParseError:
        return None
    out = {}
    for o in root.iter("object"):
        tpl = os.path.basename(o.get("template") or "")
        props = {}
        pe = o.find("properties")
        if pe is not None:
            for p in pe.findall("property"):
                props[p.get("name")] = p.get("value") if p.get("value") is not None else (p.text or "")
        typ = o.get("type") or o.get("class") or ""
        is_enemy = tpl.startswith("enemy") or typ == "enemy"
        out[int(o.get("id", 0))] = {"tpl": tpl, "enemy": is_enemy, "x": o.get("x"), "y": o.get("y"),
                                    "name": props.get("enemy", ""), "props": props}
    return out


def main():
    commits = git("log", "--reverse", "--format=%H%x09%s", "--", PREFIX).decode("utf-8", "replace").splitlines()
    commits = [c.split("\t", 1) for c in commits if c.strip()]
    hist = {}  # rel -> id -> record
    first = True
    for sha, subj in commits:
        parents = git("rev-list", "--parents", "-n", "1", sha).decode().split()
        parent = parents[1] if len(parents) > 1 else None
        if parent is None:
            names = git("ls-tree", "-r", "--name-only", sha, "--", PREFIX).decode("utf-8", "replace").splitlines()
            changed = [("A", n) for n in names]
        else:
            lines = git("diff-tree", "-r", "--no-commit-id", "--name-status", "-M", parent, sha, "--", PREFIX).decode("utf-8", "replace").splitlines()
            changed = []
            for ln in lines:
                parts = ln.split("\t")
                st = parts[0]
                if st.startswith("R"):
                    changed.append(("R", parts[2], parts[1]))
                else:
                    changed.append((st[0], parts[1]))
        for ch in changed:
            st, path = ch[0], ch[1]
            if not path.endswith(".tmx"):
                continue
            rel = path[len(PREFIX):]
            if st == "D":
                hist.pop(rel, None)
                continue
            new = objects_at(sha, path)
            if new is None:
                continue
            oldpath = ch[2] if st == "R" else path
            old = objects_at(parent, oldpath) if (parent and st != "A") else None
            if st == "R":
                oldrel = oldpath[len(PREFIX):] if oldpath.startswith(PREFIX) else oldpath
                hist[rel] = hist.pop(oldrel, {})
            rec = hist.setdefault(rel, {})
            base_tag = "BASE" if (old is None and first_commit_sha == sha) else sha
            for oid, o in new.items():
                if not o["enemy"]:
                    continue
                prev = old.get(oid) if old else None
                if prev is None or not prev["enemy"]:
                    rec[str(oid)] = {"added": base_tag if old is None and first_commit_sha == sha else sha,
                                     "added_msg": subj, "map_new_in_commit": old is None, "changes": [],
                                     "name_at_add": o["name"], "xy_at_add": [o["x"], o["y"]]}
                else:
                    what = []
                    if prev["name"] != o["name"]:
                        what.append("enemy %s -> %s" % (prev["name"], o["name"]))
                    if (prev["x"], prev["y"]) != (o["x"], o["y"]):
                        what.append("moved (%s,%s) -> (%s,%s)" % (prev["x"], prev["y"], o["x"], o["y"]))
                    pk = set(prev["props"]) | set(o["props"])
                    pch = [k for k in pk if k != "enemy" and prev["props"].get(k) != o["props"].get(k)]
                    if pch:
                        what.append("props " + ",".join(sorted(pch)))
                    if what:
                        r = rec.setdefault(str(oid), {"added": "UNKNOWN", "added_msg": "", "changes": []})
                        r["changes"].append([sha[:11], subj, "; ".join(what)])
            if old:
                for oid, o in old.items():
                    if o["enemy"] and oid not in new:
                        rec.pop(str(oid), None)
    # keep only enemies that exist at HEAD
    head = {}
    for rel, rec in hist.items():
        cur = objects_at("HEAD", PREFIX + rel)
        if cur is None:
            continue
        head[rel] = {k: v for k, v in rec.items() if int(k) in cur and cur[int(k)]["enemy"]}
        for oid, o in cur.items():
            if o["enemy"] and str(oid) not in head[rel]:
                head[rel][str(oid)] = {"added": "UNTRACKED", "added_msg": "", "changes": []}
    json.dump(head, open(os.path.join(HERE, "enemy_history.json"), "w", encoding="utf-8"), indent=1)
    print("maps:", len(head), "enemies:", sum(len(v) for v in head.values()))


first_commit_sha = git("log", "--reverse", "--format=%H", "--", PREFIX).decode().split()[0]
main()
