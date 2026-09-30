"""apply_gated_loot.py - take the loot guards WE added off boosters/chests that sit behind a gate, and mark
gated loot noGuard=true (NOT RUN by the audit session - the main session runs it).

Inputs (next to this file, produced by run_audit.py + lists.py):
  removal_list.json   our added guard objects (rounds 279/284/286b/287) paired with gated boosters/chests
  marker_list.json    every gated reward, any kind (the noGuard=true marker)
  restore_moved.json  authored enemies round 257-258 MOVED into a gated room (optional revert of the move)

usage (from anywhere; paths are absolute):
  python apply_gated_loot.py                         DRY RUN, scope room: print every change, write nothing
  python apply_gated_loot.py --apply                 remove the room-scope guards + write noGuard markers
  python apply_gated_loot.py --apply --scope all     also the SECTION items (locked wings with their own enemies)
  python apply_gated_loot.py --apply --no-marker     removal only (if MapStage does not get the noGuard check)
  python apply_gated_loot.py --apply --restore-moved also move round 258's relocated authored guards back
  python apply_gated_loot.py --check [--scope all]   re-run the audit on the maps as they are now
  --root DIR   the maps/map folder to work on (default: the repo's); e.g. a scratch copy for a dry run

Byte-exact: files are read and written as UTF-8 with NO newline translation. A removed <object> takes its own
whole lines (leading indent + its own line ending) and nothing else; a marker adds one property line (or a
properties block for a self-closing object) using that object's own indent and line ending; a restore rewrites
only the x/y attribute values in that object's start tag. Every target is verified against the list (template,
x, y, enemy) before anything is touched, and each edited file is re-parsed and compared object by object;
a map that fails either check is left untouched and reported. Idempotent: already-removed objects and
already-marked rewards are skipped.
"""
import argparse
import json
import os
import re
import subprocess
import sys
import xml.etree.ElementTree as ET

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = r"C:\TFR\repo"
DEFAULT_ROOT = os.path.join(REPO, "forge-gui", "res", "adventure", "The Forsaken Realms", "maps", "map")
MARKER = '<property name="noGuard" type="bool" value="true"/>'

TAG_RE = re.compile(r'<object\b(?:\s+[\w:.-]+\s*=\s*"[^"]*")*\s*(/?)>')
ATTR_RE = r'(\s%s\s*=\s*")([^"]*)(")'


def load(name):
    return json.load(open(os.path.join(HERE, name), encoding="utf-8"))


def in_scope(entry, scope):
    return scope == "all" or entry.get("scope") == "room"


# ---------------------------------------------------------------------------------------------- text surgery
def map_object_spans(text):
    """{id: (start, tag_end, elem_end, self_closing)} for every <object> of a MAP object layer (never the
    collision objects inside an embedded <tileset>)."""
    skip = []
    for m in re.finditer(r"<tileset\b[^>]*?(/?)>", text):
        if m.group(1) == "/":
            continue
        end = text.find("</tileset>", m.end())
        skip.append((m.start(), len(text) if end < 0 else end + len("</tileset>")))

    def skipped(pos):
        return any(a <= pos < b for a, b in skip)

    out = {}
    for m in TAG_RE.finditer(text):
        if skipped(m.start()):
            continue
        idm = re.search(r'\sid\s*=\s*"(\d+)"', m.group(0))
        if not idm:
            continue
        oid = int(idm.group(1))
        if m.group(1) == "/":
            elem_end = m.end()
        else:
            close = text.find("</object>", m.end())
            if close < 0:
                raise ValueError("unterminated <object id=%d>" % oid)
            elem_end = close + len("</object>")
        if oid in out:
            raise ValueError("object id %d appears twice" % oid)
        out[oid] = (m.start(), m.end(), elem_end, m.group(1) == "/")
    return out


def line_span(text, start, end):
    """widen [start, end) to whole lines when the element owns them; else leave it as is."""
    ls = text.rfind("\n", 0, start) + 1
    if text[ls:start].strip():
        return start, end
    nl = text.find("\n", end)
    le = len(text) if nl < 0 else nl + 1
    if text[end:le].strip():
        return start, end
    return ls, le


def newline_of(text, pos):
    nl = text.find("\n", pos)
    return "\r\n" if nl > 0 and text[nl - 1] == "\r" else "\n"


def indent_of(text, pos):
    ls = text.rfind("\n", 0, pos) + 1
    return text[ls:pos] if not text[ls:pos].strip() else ""


def attr(tag, name):
    m = re.search(ATTR_RE % re.escape(name), tag)
    return m.group(2) if m else None


# ---------------------------------------------------------------------------------------------- xml compare
def objects_by_id(text):
    root = ET.fromstring(text.encode("utf-8"))
    out = {}
    for og in root.iter("objectgroup"):
        for o in og.findall("object"):
            props = {}
            pe = o.find("properties")
            if pe is not None:
                for p in pe.findall("property"):
                    props[p.get("name")] = (p.get("type"), p.get("value"), (p.text or "").strip())
            kids = [c.tag for c in o if c.tag != "properties"]
            out[int(o.get("id"))] = (dict(o.attrib), props, kids)
    return out, root


def strip_objects(root):
    """the document with every map object removed - must be identical before and after an edit."""
    for og in root.iter("objectgroup"):
        for o in list(og.findall("object")):
            og.remove(o)
    return ET.tostring(root)


# ---------------------------------------------------------------------------------------------- one map
def plan_map(path, rel, removals, markers, restores, problems):
    raw = open(path, "rb").read()
    text = raw.decode("utf-8")
    spans = map_object_spans(text)
    edits = []  # (start, end, replacement, what)
    for r in removals:
        sp = spans.get(r["id"])
        if sp is None:
            print("   already gone      #%-5d %s" % (r["id"], r["enemy"]))
            continue
        tag = text[sp[0]:sp[1]]
        tpl = os.path.basename(attr(tag, "template") or "")
        x, y = attr(tag, "x"), attr(tag, "y")
        body = text[sp[0]:sp[2]]
        enemy = re.search(r'<property\s+name="enemy"\s+value="([^"]*)"', body)
        enemy = enemy.group(1) if enemy else None
        if (tpl, x, y, enemy) != (r["template"], r["x"], r["y"], r["enemy"]):
            problems.append("%s #%d: expected %s at (%s,%s) '%s', found %s at (%s,%s) '%s' - map skipped"
                            % (rel, r["id"], r["template"], r["x"], r["y"], r["enemy"], tpl, x, y, enemy))
            return None
        a, b = line_span(text, sp[0], sp[2])
        edits.append((a, b, "", "remove #%d %s (%s)" % (r["id"], r["enemy"], r["round"])))
    for mk in markers:
        sp = spans.get(mk["id"])
        if sp is None:
            problems.append("%s reward #%d not found - map skipped" % (rel, mk["id"]))
            return None
        tag = text[sp[0]:sp[1]]
        tpl = os.path.basename(attr(tag, "template") or "")
        if (tpl, attr(tag, "x"), attr(tag, "y")) != (mk["template"], mk["x"], mk["y"]):
            problems.append("%s reward #%d moved/changed since the audit - map skipped" % (rel, mk["id"]))
            return None
        body = text[sp[0]:sp[2]]
        if 'name="noGuard"' in body:
            continue
        nl = newline_of(text, sp[0])
        ind = indent_of(text, sp[0])
        if sp[3]:  # self-closing: <object .../>  ->  <object ...> + properties + </object>
            new_tag = re.sub(r"\s*/>$", ">", tag)
            rep = (new_tag + nl + ind + " <properties>" + nl + ind + "  " + MARKER + nl + ind + " </properties>"
                   + nl + ind + "</object>")
            edits.append((sp[0], sp[1], rep, "mark reward #%d (%s)" % (mk["id"], mk["kind"])))
        else:
            pm = re.search(r"<properties\s*>", body)
            if pm:
                pos = sp[0] + pm.end()
                pind = indent_of(text, sp[0] + pm.start())
                edits.append((pos, pos, nl + pind + " " + MARKER, "mark reward #%d (%s)" % (mk["id"], mk["kind"])))
            else:
                pos = sp[1]
                edits.append((pos, pos, nl + ind + " <properties>" + nl + ind + "  " + MARKER + nl + ind
                              + " </properties>", "mark reward #%d (%s)" % (mk["id"], mk["kind"])))
    for rs in restores:
        sp = spans.get(rs["id"])
        if sp is None:
            problems.append("%s #%d (restore) not found - map skipped" % (rel, rs["id"]))
            return None
        tag = text[sp[0]:sp[1]]
        x, y = attr(tag, "x"), attr(tag, "y")
        if (x, y) == (rs["restore_x"], rs["restore_y"]):
            continue
        if (x, y) != (rs["x"], rs["y"]):
            problems.append("%s #%d (restore) at (%s,%s), expected (%s,%s) - map skipped" % (rel, rs["id"], x, y, rs["x"], rs["y"]))
            return None
        new_tag = re.sub(ATTR_RE % "x", lambda m: m.group(1) + rs["restore_x"] + m.group(3), tag, count=1)
        new_tag = re.sub(ATTR_RE % "y", lambda m: m.group(1) + rs["restore_y"] + m.group(3), new_tag, count=1)
        edits.append((sp[0], sp[1], new_tag, "move #%d %s back to (%s,%s)" % (rs["id"], rs["enemy"], rs["restore_x"], rs["restore_y"])))
    if not edits:
        return text, text, []
    edits.sort(key=lambda e: e[0])
    for i in range(1, len(edits)):
        if edits[i][0] < edits[i - 1][1]:
            problems.append("%s: overlapping edits - map skipped" % rel)
            return None
    out, last = [], 0
    for a, b, rep, _w in edits:
        out.append(text[last:a])
        out.append(rep)
        last = b
    out.append(text[last:])
    new = "".join(out)
    # verification: object by object, and everything that is not an object
    old_objs, old_root = objects_by_id(text)
    new_objs, new_root = objects_by_id(new)
    removed = {r["id"] for r in removals if r["id"] in old_objs}
    marked = {m["id"] for m in markers}
    moved = {r["id"]: r for r in restores}
    for oid, (a, p, k) in old_objs.items():
        if oid in removed:
            if oid in new_objs:
                problems.append("%s #%d still present after removal - map skipped" % (rel, oid))
                return None
            continue
        if oid not in new_objs:
            problems.append("%s #%d vanished - map skipped" % (rel, oid))
            return None
        na, np_, nk = new_objs[oid]
        exp_a, exp_p = dict(a), dict(p)
        if oid in marked:
            exp_p["noGuard"] = ("bool", "true", "")
        if oid in moved and a.get("x") == moved[oid]["x"]:
            exp_a["x"], exp_a["y"] = moved[oid]["restore_x"], moved[oid]["restore_y"]
        if (na, np_, nk) != (exp_a, exp_p, k):
            problems.append("%s #%d changed unexpectedly - map skipped" % (rel, oid))
            return None
    if len(new_objs) != len(old_objs) - len(removed):
        problems.append("%s object count mismatch - map skipped" % rel)
        return None
    if strip_objects(old_root) != strip_objects(new_root):
        problems.append("%s non-object content changed - map skipped" % rel)
        return None
    return text, new, [e[3] for e in edits]


# ---------------------------------------------------------------------------------------------- check
def check(root, scope, want_marker):
    os.environ["TFR_MAP_ROOT"] = root
    sys.path.insert(0, HERE)
    import run_audit
    import audit
    W, rep = run_audit.run()
    removal = [r for r in load("removal_list.json") if in_scope(r, scope)]
    markers = [m for m in load("marker_list.json") if in_scope(m, scope)]
    fails = 0
    still = [r for r in removal if r["id"] in W.maps[r["map"]].byid]
    print("listed guard objects still in the maps: %d" % len(still))
    for r in still[:20]:
        print("   %s #%d %s" % (r["map"], r["id"], r["enemy"]))
    fails += bool(still)
    scoped = {(m["map"], m["id"]) for m in markers}
    ours_left, any_left, cross, unmarked = [], [], [], []
    for r, m in W.maps.items():
        if not m.rewards:
            continue
        rows = {x["id"]: x for x in rep["maps"][r]["rewards"]}
        for honor, sink in ((False, ours_left), (True, any_left)):
            for rank in range(4):
                for rid, (eid, d) in audit.replay_guards(m, rank, honor_noguard=honor).items():
                    who = audit.origin_of(HIST, r, eid)[0]
                    row, e = rows[rid], m.byid[eid]
                    if (r, rid) in scoped and row["kind"] in ("booster", "chest") and (honor or who == "OURS"):
                        sink.append((r, rid, row["kind"], eid, e["props"].get("enemy"), who, rank))
                    if honor and row["state"] != "GATED" and (not e["_closed"]) and e["_open"]:
                        cross.append((r, rid, row["kind"], eid, e["props"].get("enemy"), rank))
        for x in m.rewards:
            if (r, x["id"]) in scoped and not audit.truthy(x["props"].get("noGuard"), False):
                unmarked.append((r, x["id"], x["_kind"]))
    def report(title, rows):
        """rows: (map, reward id, kind, enemy id, enemy, ..., rank) - one line per reward, ranks merged"""
        by = {}
        for t in rows:
            by.setdefault(t[:2], set()).add(t[2:-1] + (None,))
        print("%s: %d" % (title, len(by)))
        for (mp, rid), vals in sorted(by.items())[:30]:
            print("    %s reward #%s <- %s" % (mp, rid, "; ".join("%s #%s %s" % (v[0], v[1], v[2]) for v in sorted(vals, key=str))))
        return len(by)
    fails += bool(report("in-scope gated boosters/chests paired with one of OUR guards (runtime as at HEAD)", ours_left))
    report("in-scope gated boosters/chests still given ANY guard once MapStage honours noGuard", any_left)
    report("enemies inside a gated area paired with loot OUTSIDE it (noGuard honoured)", cross)
    if want_marker:
        print("in-scope gated rewards without noGuard=true: %d" % len(unmarked))
        for t in unmarked[:30]:
            print("   ", t)
        fails += bool(unmarked)
    if os.path.normcase(os.path.abspath(root)) == os.path.normcase(os.path.abspath(DEFAULT_ROOT)):
        d = subprocess.run(["git", "-C", REPO, "diff", "--numstat", "--", root], capture_output=True, text=True).stdout
        print("git diff --numstat (added / removed lines per map):")
        print(d.rstrip() or "   (no changes)")
    print("CHECK %s" % ("PASSED" if not fails else "FAILED"))
    return 0 if not fails else 1


HIST = None


def main():
    global HIST
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--apply", action="store_true", help="write the changes (default: dry run)")
    ap.add_argument("--check", action="store_true", help="re-run the audit on the maps as they are now")
    ap.add_argument("--scope", choices=["room", "all"], default="room")
    ap.add_argument("--no-marker", action="store_true", help="do not write noGuard=true on gated rewards")
    ap.add_argument("--restore-moved", action="store_true",
                    help="move the authored guards round 257-258 pulled INTO a gated room back to their old spot")
    ap.add_argument("--root", default=DEFAULT_ROOT)
    a = ap.parse_args()
    HIST = load("enemy_history.json")
    if a.check:
        return check(a.root, a.scope, not a.no_marker)

    removal = [r for r in load("removal_list.json") if in_scope(r, a.scope)]
    markers = [] if a.no_marker else [m for m in load("marker_list.json") if in_scope(m, a.scope)]
    restores = [r for r in load("restore_moved.json") if r.get("restore_is_outside_gate") and in_scope(r, a.scope)] \
        if a.restore_moved else []
    by_map = {}
    for r in removal:
        by_map.setdefault(r["map"], [[], [], []])[0].append(r)
    for m in markers:
        by_map.setdefault(m["map"], [[], [], []])[1].append(m)
    for r in restores:
        by_map.setdefault(r["map"], [[], [], []])[2].append(r)
    problems, written, n_edits = [], 0, 0
    for rel in sorted(by_map):
        rm, mk, rs = by_map[rel]
        path = os.path.join(a.root, rel.replace("/", os.sep))
        print("== %s" % rel)
        res = plan_map(path, rel, rm, mk, rs, problems)
        if res is None:
            print("   SKIPPED (see problems)")
            continue
        old, new, what = res
        for w in what:
            print("   " + w)
        n_edits += len(what)
        if a.apply and new != old:
            with open(path, "wb") as f:
                f.write(new.encode("utf-8"))
            written += 1
    print("\n%s: scope=%s, %d map(s), %d edit(s) (%d guard removal(s) listed, %d marker(s), %d restore(s))%s"
          % ("APPLIED" if a.apply else "DRY RUN", a.scope, len(by_map), n_edits, len(removal), len(markers),
             len(restores), (", %d file(s) written" % written) if a.apply else ""))
    if problems:
        print("\nPROBLEMS (those maps were not touched):")
        for p in problems:
            print("   " + p)
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main())
