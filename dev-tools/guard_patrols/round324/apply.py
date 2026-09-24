"""apply.py - write round 322's plan.json into the .tmx files: the `enemy` value of each listed object, nothing else.

usage: python apply.py <repo root> [--plan plan.json] [--dry-run] [--log applied.json]

The repo root is REQUIRED - there is deliberately no default, so a test run against a scratch copy can never land on
the real repo by omission. Maps are found at <root>/forge-gui/res/adventure/The Forsaken Realms/maps/map/<rel>.

What it writes: for every planned object, the text between the quotes of `value="..."` in that object's own
`<property name="enemy" .../>` - the old enemy name becomes the new one (XML-escaped). Every other byte of the file is
left alone: attribute order, indentation, line endings (CRLF, LF, or the mix some maps carry from earlier tools), a
BOM if there is one. No object is added, removed or moved; position, threatRange, waypoints and every other property
stay as they are.

Safety - a map is written only if ALL of this holds, otherwise it is left untouched and reported:
  * its SHA-1 equals the one plan.py recorded (the map has not changed since it was planned) - there is no override:
    a changed map needs a fresh plan.py run;
  * every planned object is still there, is a map-level enemy placement, still has the planned OLD name as an inline
    `enemy` property (exactly one), and still stands at the planned x/y;
  * after the edit the text parses as XML, keeps every object, and every object's attributes and properties are
    identical to before except the planned `enemy` values, which now read the planned NEW names; and the bytes
    outside the edited values are identical.
"""
import argparse
import hashlib
import html
import json
import os
import re
import sys
import xml.etree.ElementTree as ET

SUB = ("forge-gui", "res", "adventure", "The Forsaken Realms", "maps", "map")
HERE = os.path.dirname(os.path.abspath(__file__))


def attr_escape(s):
    return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace('"', "&quot;")


def map_level_objects(root):
    """{id: element} for the map's own objects - not the collision objects inside an embedded tileset's <tile>."""
    inner = set()
    for t in root.iter("tile"):
        for o in t.iter("object"):
            inner.add(o)
    out = {}
    for o in root.iter("object"):
        if o in inner or o.get("id") is None:
            continue
        out[int(o.get("id"))] = o
    return out


def own_props(o):
    p = o.find("properties")
    out = []
    if p is not None:
        for pr in p.findall("property"):
            out.append((pr.get("name"), pr.get("type"), pr.get("value"), (pr.text or "").strip()))
    return out


def enemy_value(o):
    vals = [v for (n, _t, v, _x) in own_props(o) if n == "enemy"]
    return vals


def is_enemy(o):
    tpl = os.path.basename(o.get("template") or "")
    return tpl.startswith("enemy") or (o.get("type") or "") == "enemy"


def signature(objs):
    """Everything about every object, for the before/after comparison."""
    return {oid: (tuple(sorted(o.attrib.items())), tuple(own_props(o)),
                  ET.tostring(o.find("properties"), encoding="unicode") if o.find("properties") is not None else "")
            for oid, o in objs.items()}


def element_spans(text, oid):
    """(start, end) of every <object ... id="oid" ...> element in the text (self-closing or not)."""
    out = []
    for m in re.finditer(r'<object\b[^<>]*?\bid="%d"(?=[\s/>])[^<>]*>' % oid, text):
        s, e = m.start(), m.end()
        if text[e - 2:e] == "/>":
            out.append((s, e))
            continue
        close = text.find("</object>", e)
        nxt = text.find("<object", e)
        if close < 0 or (0 <= nxt < close):
            continue
        out.append((s, close + len("</object>")))
    return out


def edit_map(path, changes):
    raw = open(path, "rb").read()
    text = raw.decode("utf-8")               # a BOM stays in the text as U+FEFF and is written back as it was
    root = ET.fromstring(raw)
    objs = map_level_objects(root)
    errors = []
    for c in changes:
        o = objs.get(c["id"])
        if o is None:
            errors.append("object %d is gone" % c["id"])
            continue
        if not is_enemy(o):
            errors.append("object %d is no longer an enemy" % c["id"])
            continue
        vals = enemy_value(o)
        if len(vals) != 1 or vals[0] is None or vals[0].strip() != c["old"]:
            errors.append("object %d has enemy %r, the plan expects %r" % (c["id"], vals, c["old"]))
            continue
        if abs(float(o.get("x", 0)) - c["x"]) > 0.01 or abs(float(o.get("y", 0)) - c["y"]) > 0.01:
            errors.append("object %d moved since the plan" % c["id"])
    if errors:
        return None, errors, []

    edits = []                               # (start, end, replacement) in text offsets
    for c in changes:
        cands = []
        for s, e in element_spans(text, c["id"]):
            elem = text[s:e]
            tags = list(re.finditer(r'<property\b[^<>]*?\bname="enemy"(?=[\s/>])[^<>]*>', elem))
            if len(tags) != 1:
                continue
            vm = re.search(r'\bvalue="([^"]*)"', tags[0].group(0))
            if not vm or html.unescape(vm.group(1)).strip() != c["old"]:
                continue
            a = s + tags[0].start() + vm.start(1)
            b = s + tags[0].start() + vm.end(1)
            cands.append((a, b))
        if len(cands) != 1:
            errors.append("object %d: %d text matches for its enemy property (need exactly 1)" % (c["id"], len(cands)))
            continue
        a, b = cands[0]
        edits.append((a, b, attr_escape(c["new"]), c))
    if errors:
        return None, errors, []

    new_text = text
    for a, b, rep, _c in sorted(edits, key=lambda t: -t[0]):
        new_text = new_text[:a] + rep + new_text[b:]

    # --- verify before anything is written
    # 1. the bytes outside the edited values are identical
    pos_old = pos_new = 0
    for a, b, rep, _c in sorted(edits, key=lambda t: t[0]):
        seg = a - pos_old
        if text[pos_old:a] != new_text[pos_new:pos_new + seg]:
            return None, ["text outside the edits changed (internal error)"], []
        pos_new += seg + len(rep)
        pos_old = b
    if text[pos_old:] != new_text[pos_new:]:
        return None, ["text after the last edit changed (internal error)"], []
    data = new_text.encode("utf-8")
    # 2. the result parses, and only the planned enemy values differ
    try:
        new_root = ET.fromstring(data)
    except ET.ParseError as ex:
        return None, ["edited text does not parse: %s" % ex], []
    new_objs = map_level_objects(new_root)
    if set(new_objs) != set(objs):
        return None, ["object ids changed: -%s +%s" % (sorted(set(objs) - set(new_objs)),
                                                       sorted(set(new_objs) - set(objs)))], []
    before, after = signature(objs), signature(new_objs)
    planned = {c["id"]: c for c in changes}
    for oid in objs:
        if oid in planned:
            vals = enemy_value(new_objs[oid])
            if vals != [planned[oid]["new"]]:
                return None, ["object %d reads %r after the edit, not %r" % (oid, vals, planned[oid]["new"])], []
            ob, nb = before[oid], after[oid]
            if ob[0] != nb[0]:
                return None, ["object %d: attributes changed" % oid], []
            po = [p for p in ob[1] if p[0] != "enemy"]
            pn = [p for p in nb[1] if p[0] != "enemy"]
            if po != pn or [p[0] for p in ob[1]] != [p[0] for p in nb[1]]:
                return None, ["object %d: other properties changed" % oid], []
        elif before[oid] != after[oid]:
            return None, ["object %d changed but was not planned" % oid], []
    if new_root.get("nextobjectid") != root.get("nextobjectid"):
        return None, ["nextobjectid changed"], []
    done = [{"id": c["id"], "old": c["old"], "new": c["new"], "offset": a} for a, _b, _r, c in edits]
    return data, [], done


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("root", help="repo root (REQUIRED - no default)")
    ap.add_argument("--plan", default=os.path.join(HERE, "plan.json"))
    ap.add_argument("--dry-run", action="store_true", help="check everything, write nothing")
    ap.add_argument("--log", help="write what was done here (JSON)")
    a = ap.parse_args()

    maps_root = os.path.join(os.path.abspath(a.root), *SUB)
    if not os.path.isdir(maps_root):
        raise SystemExit("%s has no %s" % (a.root, "/".join(SUB)))
    plan = json.load(open(a.plan, encoding="utf-8"))
    written, skipped, failed = {}, {}, {}
    n_changes = 0
    for rel, entry in sorted(plan["maps"].items()):
        path = os.path.join(maps_root, *rel.split("/"))
        if not os.path.exists(path):
            failed[rel] = ["map not found"]
            continue
        sha = hashlib.sha1(open(path, "rb").read()).hexdigest()
        if sha != entry["sha1"]:
            skipped[rel] = "changed since it was planned (sha1 %s, planned %s) - re-run plan.py" % (sha[:10],
                                                                                                    entry["sha1"][:10])
            continue
        data, errors, done = edit_map(path, entry["changes"])
        if data is None:
            failed[rel] = errors
            continue
        if not a.dry_run:
            with open(path, "wb") as fh:
                fh.write(data)
        written[rel] = done
        n_changes += len(done)
        print("%-52s %s%s" % (rel, "(dry run) " if a.dry_run else "",
                              ", ".join("#%d %s -> %s" % (d["id"], d["old"], d["new"]) for d in done)))
    for rel, why in skipped.items():
        print("SKIPPED %s: %s" % (rel, why))
    for rel, why in failed.items():
        print("FAILED  %s: %s" % (rel, "; ".join(why)))
    print("\n%s: %d map(s), %d enemy name(s) %s; %d map(s) skipped (changed since planning), %d failed" % (
        "DRY RUN" if a.dry_run else "APPLIED", len(written), n_changes, "checked" if a.dry_run else "written",
        len(skipped), len(failed)))
    if a.log:
        json.dump({"written": written, "skipped": skipped, "failed": failed, "dry_run": a.dry_run},
                  open(a.log, "w", encoding="utf-8"), indent=1)
    return 1 if (skipped or failed) else 0


if __name__ == "__main__":
    sys.exit(main())
