"""apply.py - write plan.json into the .tmx files (round 316).

usage: python apply.py <repo root> [--plan plan.json] [--with-relocations] [--dry-run] [--force] [--log applied.json]

The repo root is REQUIRED - there is deliberately no default, so a test run against a scratch copy can never land
on the real repo by omission. Maps are found at <root>/forge-gui/res/adventure/The Forsaken Realms/maps/map/<rel>.

What it writes, exactly the way the maps already write it:
  patrol     one `<object id=".." template="<the map's own path to>/waypoint.tx" x=".." y=".."/>` per waypoint,
             appended to the object layer that holds the enemy, at the file's usual object indentation; and a
             `<property name="waypoints" value="id,id"/>` on the enemy (an empty authored one is filled in, a
             self-closing object gets a <properties> block). Ids start at max(nextobjectid, highest id + 1) -
             never below nextobjectid, so an id Tiled has handed out is never reused - and nextobjectid is moved
             past the last one.
  removal    the enemy's whole <object> element, with its line.
  relocation (only with --with-relocations) the enemy's x/y attributes, nothing else.
Every other byte is left alone. The text is edited RAW: 120 of the maps use CRLF and 45 of those also carry a
few LF-only lines (left by earlier tools), so nothing is normalized - an inserted line takes the ending of the
line it follows, a removed line takes its own ending with it, and no BOM is added or lost.

Safety:
  * each map's SHA-1 must equal the one plan.py recorded (the map has not changed since it was planned), else the
    map is skipped - --force overrides, and still runs every per-object check below;
  * every touched enemy must still be there with the planned name and position, and a patrolled one must not
    already have a route;
  * the edited text is re-parsed before it is written: it must be well-formed XML, keep every object id it had
    (minus the removals), and every new route must resolve to waypoint objects at the planned coordinates.
    Any failure leaves that file untouched.
"""
import argparse
import hashlib
import json
import os
import re
import sys
import xml.etree.ElementTree as ET

SUB = ("forge-gui", "res", "adventure", "The Forsaken Realms", "maps", "map")
HERE = os.path.dirname(os.path.abspath(__file__))


def fmt(v):
    """add_booster_guards.fmt(): an integral coordinate as an int, else up to 4 decimals, trailing zeros off."""
    v = float(v)
    return str(int(v)) if v == int(v) else ("%.4f" % v).rstrip("0").rstrip(".")


def start_tag(text, oid):
    """(start, end) of <object ... id="oid" ...> or its self-closing form."""
    m = re.search(r'<object\b[^<>]*?\bid="%d"(?=[\s/>])[^<>]*>' % oid, text)
    return (m.start(), m.end()) if m else None


def element_span(text, oid):
    """(start, end, self_closing) of the whole <object> element for id `oid`."""
    st = start_tag(text, oid)
    if not st:
        return None
    s, e = st
    if text[e - 2:e] == "/>":
        return s, e, True
    close = text.find("</object>", e)
    if close < 0:
        return None
    nxt = text.find("<object", e)
    if 0 <= nxt < close:
        return None                       # an unterminated element; refuse rather than guess
    return s, close + len("</object>"), False


def line_start(text, pos):
    return text.rfind("\n", 0, pos) + 1


def line_indent(text, pos):
    ls = line_start(text, pos)
    return text[ls:pos] if text[ls:pos].strip() == "" else ""


def eol_before(text, pos):
    """The line ending of the line that ends just before `pos` (a line start) - what an inserted line copies."""
    if pos >= 2 and text[pos - 2:pos] == "\r\n":
        return "\r\n"
    return "\n"


def eol_of_line(text, pos):
    """The line ending of the line containing `pos`."""
    nl = text.find("\n", pos)
    if nl < 0:
        return "\n"
    return "\r\n" if nl > 0 and text[nl - 1] == "\r" else "\n"


def props_of(elem):
    out = {}
    p = elem.find("properties")
    if p is not None:
        for pr in p.findall("property"):
            v = pr.get("value")
            out[pr.get("name")] = v if v is not None else (pr.text or "")
    return out


def check_enemy(root, oid, name, xy):
    """The object must still be the planned enemy at the planned place."""
    for o in root.iter("object"):
        if o.get("id") == str(oid):
            props = props_of(o)
            tpl = os.path.basename(o.get("template") or "")
            if not tpl.startswith("enemy") and (o.get("type") or "") != "enemy":
                return "object %d is no longer an enemy" % oid
            if (props.get("enemy") or "").strip() != name:
                return "object %d is now %r, not %r" % (oid, props.get("enemy"), name)
            if abs(float(o.get("x", 0)) - xy[0]) > 0.01 or abs(float(o.get("y", 0)) - xy[1]) > 0.01:
                return "object %d moved since the plan" % oid
            return None
    return "object %d is gone" % oid


def waypoint_template(text, enemy_tpl, map_dir):
    m = re.search(r'template="((?:[^"]*/)?waypoint\.tx)"', text)
    if m:
        return m.group(1)
    cand = re.sub(r'[^/]+\.tx$', 'waypoint.tx', enemy_tpl or "")
    if cand and os.path.exists(os.path.join(map_dir, cand)):
        return cand
    m = re.search(r'template="((?:\.\./)+common/maps/obj/)[^"]+\.tx"', text)
    if m and os.path.exists(os.path.join(map_dir, m.group(1) + "waypoint.tx")):
        return m.group(1) + "waypoint.tx"
    return None


def edit_map(path, entry, with_relocations):
    raw = open(path, "rb").read()
    text = raw.decode("utf-8")                   # a BOM, if any, stays in the text as U+FEFF and is written back
    root = ET.fromstring(raw)
    map_dir = os.path.dirname(path)
    log = {"patrols": [], "removals": [], "relocations": [], "errors": []}

    patrols = entry.get("patrols", [])
    removals = entry.get("removals", [])
    moves = entry.get("relocations", []) if with_relocations else []
    for p in patrols:
        err = check_enemy(root, p["enemy_id"], p["enemy"], p["home"])
        if not err:
            o = next(o for o in root.iter("object") if o.get("id") == str(p["enemy_id"]))
            if (props_of(o).get("waypoints") or "").strip():
                err = "object %d already has a route" % p["enemy_id"]
        if err:
            log["errors"].append(err)
    for r in removals:
        err = check_enemy(root, r["enemy_id"], r["enemy"], r["home"])
        if err:
            log["errors"].append(err)
    for r in moves:
        err = check_enemy(root, r["enemy_id"], r["enemy"], r["from"])
        if err:
            log["errors"].append(err)
    if log["errors"]:
        return None, log

    ids = [int(o.get("id")) for o in root.iter("object") if o.get("id")]
    next_id = max(int(root.get("nextobjectid", "0") or 0), (max(ids) + 1) if ids else 1)
    first_id = next_id
    # new objects take the file's usual object indentation (Tiled's is two spaces; earlier tools left a few
    # objects at three, so the enemy's own line is not a safe model)
    counts = {}
    for ind_ in re.findall(r'\n([ \t]*)<object ', text):
        counts[ind_] = counts.get(ind_, 0) + 1
    obj_ind = max(counts, key=counts.get) if counts else "  "

    # 1. patrols: the route onto the enemy, then its waypoints at the end of the enemy's own layer
    for p in patrols:
        span = element_span(text, p["enemy_id"])
        if not span:
            log["errors"].append("object %d: element not found in the text" % p["enemy_id"])
            return None, log
        s, e, selfclose = span
        tpl_m = re.search(r'template="([^"]+)"', text[s:e])
        wtpl = waypoint_template(text, tpl_m.group(1) if tpl_m else "", map_dir)
        if not wtpl:
            log["errors"].append("no waypoint.tx template reachable from this map")
            return None, log
        new_ids = []
        for w in p["waypoints"]:
            w["id"] = next_id
            new_ids.append(next_id)
            next_id += 1
        route = ",".join(str(i) for i in new_ids)
        ind = line_indent(text, s)
        if selfclose:
            nl = eol_of_line(text, s)
            opened = text[s:e][:-2].rstrip() + ">"
            block = ('%s%s%s <properties>%s%s  <property name="waypoints" value="%s"/>%s%s </properties>%s%s</object>'
                     % (opened, nl, ind, nl, ind, route, nl, ind, nl, ind))
            text = text[:s] + block + text[e:]
        else:
            elem = text[s:e]
            m_empty = re.search(r'<property name="waypoints" value=""\s*/>', elem)
            if m_empty:
                elem = elem[:m_empty.start()] + '<property name="waypoints" value="%s"/>' % route + elem[m_empty.end():]
            elif "</properties>" in elem:
                pi = elem.find("<property ")
                ci = elem.rfind("</properties>")
                cind = line_indent(elem, ci)
                pind = line_indent(elem, pi) if pi >= 0 else ""
                if not pind:
                    pind = cind + " "
                at = ci - len(cind)                                   # start of the </properties> line
                nl = eol_before(elem, at)
                elem = elem[:at] + '%s<property name="waypoints" value="%s"/>%s' % (pind, route, nl) + elem[at:]
            else:
                close = elem.rfind("</object>")
                cind = line_indent(elem, close)
                at = close - len(cind)
                nl = eol_before(elem, at)
                elem = elem[:at] + ('%s <properties>%s%s  <property name="waypoints" value="%s"/>%s%s </properties>%s'
                                    % (ind, nl, ind, route, nl, ind, nl)) + elem[at:]
            text = text[:s] + elem + text[e:]
        s2 = start_tag(text, p["enemy_id"])[0]
        gclose = text.find("</objectgroup>", s2)
        at = line_start(text, gclose) if not text[line_start(text, gclose):gclose].strip() else gclose
        nl = eol_before(text, at)
        lines = "".join('%s<object id="%d" template="%s" x="%s" y="%s"/>%s' % (obj_ind, w["id"], wtpl,
                                                                              fmt(w["x"]), fmt(w["y"]), nl)
                        for w in p["waypoints"])
        text = text[:at] + lines + text[at:]
        log["patrols"].append({"enemy_id": p["enemy_id"], "enemy": p["enemy"], "waypoints": new_ids,
                               "route": route, "template": wtpl})

    # 2. removals: the element and its own line ending
    for r in removals:
        span = element_span(text, r["enemy_id"])
        if not span:
            log["errors"].append("object %d: element not found for removal" % r["enemy_id"])
            return None, log
        s, e, _sc = span
        ls = line_start(text, s)
        if text[ls:s].strip():
            ls = s
        if text[e:e + 2] == "\r\n":
            e += 2
        elif text[e:e + 1] == "\n":
            e += 1
        text = text[:ls] + text[e:]
        log["removals"].append({"enemy_id": r["enemy_id"], "enemy": r["enemy"]})

    # 3. relocations (optional)
    for r in moves:
        st = start_tag(text, r["enemy_id"])
        tag = text[st[0]:st[1]]
        new = re.sub(r'(\sx=")[-0-9.]+(")', lambda m: m.group(1) + fmt(r["to"][0]) + m.group(2), tag, count=1)
        new = re.sub(r'(\sy=")[-0-9.]+(")', lambda m: m.group(1) + fmt(r["to"][1]) + m.group(2), new, count=1)
        text = text[:st[0]] + new + text[st[1]:]
        log["relocations"].append({"enemy_id": r["enemy_id"], "enemy": r["enemy"], "to": r["to"]})

    if next_id != first_id:
        text, n = re.subn(r'(<map\b[^>]*?\bnextobjectid=")\d+(")', lambda m: m.group(1) + str(next_id) + m.group(2),
                          text, count=1)
        if n != 1:
            log["errors"].append("nextobjectid not found on <map>")
            return None, log

    # verify before anything is written
    data = text.encode("utf-8")
    try:
        new_root = ET.fromstring(data)
    except ET.ParseError as ex:
        log["errors"].append("edited text does not parse: %s" % ex)
        return None, log
    new_ids = {int(o.get("id")) for o in new_root.iter("object") if o.get("id")}
    gone = set(ids) - new_ids - {r["enemy_id"] for r in removals}
    if gone:
        log["errors"].append("objects lost by the edit: %s" % sorted(gone))
        return None, log
    if any(r["enemy_id"] in new_ids for r in removals):
        log["errors"].append("a removal did not remove")
        return None, log
    byid = {int(o.get("id")): o for o in new_root.iter("object") if o.get("id")}
    for p in patrols:
        o = byid[p["enemy_id"]]
        if props_of(o).get("waypoints") != ",".join(str(w["id"]) for w in p["waypoints"]):
            log["errors"].append("object %d: route not written as planned" % p["enemy_id"])
            return None, log
        for w in p["waypoints"]:
            wo = byid.get(w["id"])
            if wo is None or "waypoint.tx" not in (wo.get("template") or "") or \
                    abs(float(wo.get("x")) - w["x"]) > 0.01 or abs(float(wo.get("y")) - w["y"]) > 0.01:
                log["errors"].append("waypoint %d not written as planned" % w["id"])
                return None, log
    for r in moves:
        o = byid[r["enemy_id"]]
        if abs(float(o.get("x")) - r["to"][0]) > 0.01 or abs(float(o.get("y")) - r["to"][1]) > 0.01:
            log["errors"].append("relocation of %d not written as planned" % r["enemy_id"])
            return None, log
    if int(new_root.get("nextobjectid", "0")) <= max(new_ids):
        log["errors"].append("nextobjectid is not past the highest id")
        return None, log
    log["ids"] = [first_id, next_id - 1] if next_id != first_id else None
    return data, log


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("root", help="repo root (REQUIRED - no default)")
    ap.add_argument("--plan", default=os.path.join(HERE, "plan.json"))
    ap.add_argument("--with-relocations", action="store_true", help="also apply the optional relocations")
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--force", action="store_true", help="apply even if a map changed since it was planned")
    ap.add_argument("--log", help="write what was done here (JSON)")
    a = ap.parse_args()

    maps_root = os.path.join(os.path.abspath(a.root), *SUB)
    if not os.path.isdir(maps_root):
        raise SystemExit("%s has no %s" % (a.root, "/".join(SUB)))
    plan = json.load(open(a.plan, encoding="utf-8"))
    done, skipped, failed = {}, {}, {}
    n_p = n_r = n_m = n_w = 0
    for rel, entry in plan["maps"].items():
        has_work = entry.get("patrols") or entry.get("removals") or (a.with_relocations and entry.get("relocations"))
        if not has_work:
            continue
        path = os.path.join(maps_root, *rel.split("/"))
        if not os.path.exists(path):
            failed[rel] = ["map not found"]
            continue
        sha = hashlib.sha1(open(path, "rb").read()).hexdigest()
        if sha != entry["sha1"] and not a.force:
            skipped[rel] = "changed since it was planned (sha1 %s, planned %s) - re-run plan.py" % (sha[:10],
                                                                                                    entry["sha1"][:10])
            continue
        entry = json.loads(json.dumps(entry))                       # the id assignment writes into it
        data, log = edit_map(path, entry, a.with_relocations)
        if data is None:
            failed[rel] = log["errors"]
            continue
        if not a.dry_run:
            with open(path, "wb") as fh:
                fh.write(data)
        done[rel] = log
        n_p += len(log["patrols"])
        n_r += len(log["removals"])
        n_m += len(log["relocations"])
        n_w += sum(len(x["waypoints"]) for x in log["patrols"])
        print("%-52s %s%d patrol(s), %d removal(s)%s, ids %s" % (
            rel, "(dry run) " if a.dry_run else "", len(log["patrols"]), len(log["removals"]),
            (", %d relocation(s)" % len(log["relocations"])) if a.with_relocations else "",
            "%d-%d" % tuple(log["ids"]) if log.get("ids") else "-"))
    for rel, why in skipped.items():
        print("SKIPPED %s: %s" % (rel, why))
    for rel, why in failed.items():
        print("FAILED  %s: %s" % (rel, "; ".join(why)))
    print("\n%s: %d map(s) written, %d patrol(s) (%d waypoint objects), %d removal(s), %d relocation(s); "
          "%d skipped, %d failed" % ("DRY RUN" if a.dry_run else "APPLIED", len(done), n_p, n_w, n_r, n_m,
                                     len(skipped), len(failed)))
    if a.log:
        json.dump({"done": done, "skipped": skipped, "failed": failed}, open(a.log, "w", encoding="utf-8"), indent=1)
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
