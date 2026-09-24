"""apply.py - write round 329's patrol-route plan (plan.json) into the .tmx files.

usage: python apply.py <repo root> [--plan plan.json] [--dry-run] [--force] [--log applied.json] [--maps rel ...]

The repo root is REQUIRED - there is deliberately no default, so a test run against a scratch copy can never land
on the real repo by omission. Maps are found at <root>/forge-gui/res/adventure/The Forsaken Realms/maps/map/<rel>.

Only four kinds of edit, each exactly the way the maps already write it (plan.json's per-map "edits"):
  move_waypoints  a waypoint object's x/y attributes, nothing else (the route that names it is unchanged);
  new_waypoints   one `<object id=".." template="<the map's own path to>/waypoint.tx" x=".." y=".."/>` line per
                  waypoint, appended to the object layer that holds the enemy it is for, at the file's usual
                  object indentation. Ids start at max(nextobjectid, highest id + 1) - an id Tiled has handed out
                  is never reused - and nextobjectid moves past the last one;
  set_route       an enemy's `<property name="waypoints" value=".."/>` value (the plan's temporary negative ids
                  for new waypoints are resolved to the real ones);
  drop_route      that property's whole line goes (the template's empty default applies: the enemy stands).
Every other byte is left alone. The text is edited RAW: many maps use CRLF and some mix in LF-only lines left by
earlier tools, so nothing is normalized - an inserted line takes the ending of the line before it, a removed line
takes its own ending with it, and a BOM is kept as it was.

Safety:
  * each map's SHA-1 must equal the one the plan recorded (the map has not changed since it was planned), else
    the map is skipped - --force overrides it and still runs every check below;
  * every waypoint to move must still be a waypoint at the planned "from" position; every route to change must
    still be on the planned enemy with exactly the planned old value;
  * the edited text is re-parsed before it is written: well-formed XML, every object id it had still there, the
    moved waypoints at their new places, the new waypoint objects present, each route value as planned (or gone),
    nextobjectid past the highest id. Any failure leaves that file untouched.
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
    m = re.search(r'<object\b[^<>]*?\bid="%d"(?=[\s/>])[^<>]*>' % oid, text)
    return (m.start(), m.end()) if m else None


def element_span(text, oid):
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
        return None
    return s, close + len("</object>"), False


def line_start(text, pos):
    return text.rfind("\n", 0, pos) + 1


def eol_before(text, pos):
    if pos >= 2 and text[pos - 2:pos] == "\r\n":
        return "\r\n"
    return "\n"


def props_of(elem):
    out = {}
    p = elem.find("properties")
    if p is not None:
        for pr in p.findall("property"):
            v = pr.get("value")
            out[pr.get("name")] = v if v is not None else (pr.text or "")
    return out


def resolve(value, ids):
    """Temporary ids (negative) -> the real new object ids."""
    out = []
    for tok in value.split(","):
        t = tok.strip()
        if re.fullmatch(r"-\d+", t):
            out.append(tok.replace(t, str(ids[int(t)])))
        else:
            out.append(tok)
    return ",".join(out)


def edit_map(path, entry):
    raw = open(path, "rb").read()
    text = raw.decode("utf-8")
    root = ET.fromstring(raw)
    ed = entry["edits"]
    log = {"moved": [], "added": [], "set": [], "dropped": [], "errors": []}
    objs = {int(o.get("id")): o for o in root.iter("object") if o.get("id")}

    # ---- pre-checks against the live file
    for mv in ed["move_waypoints"]:
        o = objs.get(mv["id"])
        if o is None:
            log["errors"].append("waypoint %d is gone" % mv["id"])
            continue
        if os.path.basename(o.get("template") or "") != "waypoint.tx" and (o.get("type") or o.get("class")) != "waypoint":
            log["errors"].append("object %d is not a waypoint" % mv["id"])
            continue
        if abs(float(o.get("x", 0)) - mv["from"][0]) > 0.01 or abs(float(o.get("y", 0)) - mv["from"][1]) > 0.01:
            log["errors"].append("waypoint %d moved since the plan" % mv["id"])
    for rt in ed["set_route"] + ed["drop_route"]:
        o = objs.get(rt["enemy_id"])
        if o is None:
            log["errors"].append("enemy %d is gone" % rt["enemy_id"])
            continue
        pr = props_of(o)
        if (pr.get("enemy") or "").strip() != rt["enemy"]:
            log["errors"].append("object %d is now %r, not %r" % (rt["enemy_id"], pr.get("enemy"), rt["enemy"]))
        if (pr.get("waypoints") or "") != rt["old"]:
            log["errors"].append("enemy %d's route is %r, not the planned %r" % (rt["enemy_id"], pr.get("waypoints"),
                                                                            rt["old"]))
    if log["errors"]:
        return None, log

    ids = sorted(objs)
    next_id = max(int(root.get("nextobjectid", "0") or 0), (max(ids) + 1) if ids else 1)
    first_id = next_id
    new_ids = {}
    for w in ed["new_waypoints"]:
        new_ids[w["temp_id"]] = next_id
        next_id += 1

    # ---- 1. moves: x/y of the waypoint's start tag only
    for mv in ed["move_waypoints"]:
        st = start_tag(text, mv["id"])
        if not st:
            log["errors"].append("waypoint %d: start tag not found" % mv["id"])
            return None, log
        tag = text[st[0]:st[1]]
        new, n1 = re.subn(r'(\sx=")[-0-9.eE+]+(")', lambda m: m.group(1) + fmt(mv["to"][0]) + m.group(2), tag, count=1)
        new, n2 = re.subn(r'(\sy=")[-0-9.eE+]+(")', lambda m: m.group(1) + fmt(mv["to"][1]) + m.group(2), new, count=1)
        if n1 != 1 or n2 != 1:
            log["errors"].append("waypoint %d: x/y attributes not found" % mv["id"])
            return None, log
        text = text[:st[0]] + new + text[st[1]:]
        log["moved"].append({"id": mv["id"], "to": mv["to"]})

    # ---- 2. route values (set / drop) inside the enemy's own element
    for rt in ed["set_route"] + [dict(d, drop=True) for d in ed["drop_route"]]:
        span = element_span(text, rt["enemy_id"])
        if not span or span[2]:
            log["errors"].append("enemy %d: element not found (or has no properties)" % rt["enemy_id"])
            return None, log
        s, e, _sc = span
        elem = text[s:e]
        pat = re.compile(r'<property name="waypoints" value="%s"\s*/>' % re.escape(rt["old"]))
        hits = list(pat.finditer(elem))
        if len(hits) != 1:
            log["errors"].append("enemy %d: %d waypoints properties with the planned value" % (rt["enemy_id"], len(hits)))
            return None, log
        h = hits[0]
        if rt.get("drop"):
            ls = line_start(elem, h.start())
            if elem[ls:h.start()].strip():
                log["errors"].append("enemy %d: the waypoints property shares its line" % rt["enemy_id"])
                return None, log
            le = h.end()
            if elem[le:le + 2] == "\r\n":
                le += 2
            elif elem[le:le + 1] == "\n":
                le += 1
            else:
                log["errors"].append("enemy %d: the waypoints property is not alone on its line" % rt["enemy_id"])
                return None, log
            elem = elem[:ls] + elem[le:]
            log["dropped"].append({"enemy_id": rt["enemy_id"], "old": rt["old"]})
        else:
            value = resolve(rt["new"], new_ids)
            elem = elem[:h.start()] + '<property name="waypoints" value="%s"/>' % value + elem[h.end():]
            log["set"].append({"enemy_id": rt["enemy_id"], "old": rt["old"], "new": value})
        text = text[:s] + elem + text[e:]

    # ---- 3. new waypoint objects at the end of the enemy's own layer
    if ed["new_waypoints"]:
        tm = re.search(r'template="((?:[^"]*/)?waypoint\.tx)"', text)
        if not tm:
            log["errors"].append("no waypoint.tx template reference in this map to copy")
            return None, log
        wtpl = tm.group(1)
        counts = {}
        for ind_ in re.findall(r'\n([ \t]*)<object ', text):
            counts[ind_] = counts.get(ind_, 0) + 1
        obj_ind = max(counts, key=counts.get) if counts else "  "
        for w in ed["new_waypoints"]:
            st = start_tag(text, w["layer_of_enemy"])
            if not st:
                log["errors"].append("enemy %s for new waypoint not found" % w["layer_of_enemy"])
                return None, log
            gclose = text.find("</objectgroup>", st[0])
            at = line_start(text, gclose) if not text[line_start(text, gclose):gclose].strip() else gclose
            nl = eol_before(text, at)
            line = '%s<object id="%d" template="%s" x="%s" y="%s"/>%s' % (obj_ind, new_ids[w["temp_id"]], wtpl,
                                                                          fmt(w["xy"][0]), fmt(w["xy"][1]), nl)
            text = text[:at] + line + text[at:]
            log["added"].append({"temp_id": w["temp_id"], "id": new_ids[w["temp_id"]], "xy": w["xy"]})
        text, n = re.subn(r'(<map\b[^>]*?\bnextobjectid=")\d+(")', lambda m: m.group(1) + str(next_id) + m.group(2),
                          text, count=1)
        if n != 1:
            log["errors"].append("nextobjectid not found on <map>")
            return None, log

    # ---- verify before anything is written
    data = text.encode("utf-8")
    try:
        nroot = ET.fromstring(data)
    except ET.ParseError as ex:
        log["errors"].append("edited text does not parse: %s" % ex)
        return None, log
    byid = {int(o.get("id")): o for o in nroot.iter("object") if o.get("id")}
    gone = set(ids) - set(byid)
    if gone:
        log["errors"].append("objects lost by the edit: %s" % sorted(gone))
        return None, log
    for mv in ed["move_waypoints"]:
        o = byid[mv["id"]]
        if abs(float(o.get("x")) - mv["to"][0]) > 0.01 or abs(float(o.get("y")) - mv["to"][1]) > 0.01:
            log["errors"].append("waypoint %d not at its planned place" % mv["id"])
            return None, log
    for w in ed["new_waypoints"]:
        o = byid.get(new_ids[w["temp_id"]])
        if o is None or os.path.basename(o.get("template") or "") != "waypoint.tx" or \
                abs(float(o.get("x")) - w["xy"][0]) > 0.01 or abs(float(o.get("y")) - w["xy"][1]) > 0.01:
            log["errors"].append("new waypoint %s not written as planned" % w["temp_id"])
            return None, log
    for rt in ed["set_route"]:
        if props_of(byid[rt["enemy_id"]]).get("waypoints") != resolve(rt["new"], new_ids):
            log["errors"].append("enemy %d: route not written as planned" % rt["enemy_id"])
            return None, log
        for tok in resolve(rt["new"], new_ids).replace(" ", "").split(","):
            for i in (tok[1:].split("-") if tok.startswith("r") else ([tok] if re.fullmatch(r"\d+", tok) else [])):
                o = byid.get(int(i))
                if o is None or os.path.basename(o.get("template") or "") != "waypoint.tx":
                    log["errors"].append("enemy %d: route names %s, which is no waypoint" % (rt["enemy_id"], i))
                    return None, log
    for rt in ed["drop_route"]:
        if "waypoints" in props_of(byid[rt["enemy_id"]]):
            log["errors"].append("enemy %d: route not dropped" % rt["enemy_id"])
            return None, log
    if int(nroot.get("nextobjectid", "0")) <= max(byid):
        log["errors"].append("nextobjectid is not past the highest id")
        return None, log
    log["ids"] = [first_id, next_id - 1] if next_id != first_id else None
    return data, log


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("root", help="repo root (REQUIRED - no default)")
    ap.add_argument("--plan", default=os.path.join(HERE, "plan.json"))
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--force", action="store_true", help="apply even if a map changed since it was planned")
    ap.add_argument("--log", help="write what was done here (JSON)")
    ap.add_argument("--maps", nargs="*", help="only these maps (paths relative to maps/map)")
    a = ap.parse_args()

    maps_root = os.path.join(os.path.abspath(a.root), *SUB)
    if not os.path.isdir(maps_root):
        raise SystemExit("%s has no %s" % (a.root, "/".join(SUB)))
    plan = json.load(open(a.plan, encoding="utf-8"))
    done, skipped, failed = {}, {}, {}
    n = {"moved": 0, "added": 0, "set": 0, "dropped": 0}
    for rel, entry in plan["maps"].items():
        if a.maps and rel not in a.maps:
            continue
        ed = entry.get("edits") or {}
        if not any(ed.get(k) for k in ("move_waypoints", "new_waypoints", "set_route", "drop_route")):
            continue
        path = os.path.join(maps_root, *rel.split("/"))
        if not os.path.exists(path):
            failed[rel] = ["map not found"]
            continue
        sha = hashlib.sha1(open(path, "rb").read()).hexdigest()
        if sha != entry["sha1"] and not a.force:
            skipped[rel] = "changed since it was planned (sha1 %s, planned %s) - re-run the planner" % (
                sha[:10], entry["sha1"][:10])
            continue
        data, log = edit_map(path, entry)
        if data is None:
            failed[rel] = log["errors"]
            continue
        if not a.dry_run:
            with open(path, "wb") as fh:
                fh.write(data)
        done[rel] = log
        for k in n:
            n[k] += len(log[k])
        print("%-58s %s%d moved, %d new, %d rewritten, %d dropped%s" % (
            rel, "(dry run) " if a.dry_run else "", len(log["moved"]), len(log["added"]), len(log["set"]),
            len(log["dropped"]), (", ids %d-%d" % tuple(log["ids"])) if log.get("ids") else ""))
    for rel, why in skipped.items():
        print("SKIPPED %s: %s" % (rel, why))
    for rel, why in failed.items():
        print("FAILED  %s: %s" % (rel, "; ".join(why)))
    print("\n%s: %d map(s) written - %d waypoints moved, %d new waypoint objects, %d routes rewritten, "
          "%d routes dropped; %d skipped, %d failed" % ("DRY RUN" if a.dry_run else "APPLIED", len(done), n["moved"],
                                                       n["added"], n["set"], n["dropped"], len(skipped), len(failed)))
    if a.log:
        json.dump({"done": done, "skipped": skipped, "failed": failed}, open(a.log, "w", encoding="utf-8"), indent=1)
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
