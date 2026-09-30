"""dialogs.py - what a map's dialogs can delete/activate, and under which conditions (read-only helper).

A dialog is a list of root nodes; MapDialog.activate() loads every root whose condition holds, a node's `action`
runs when the node is loaded, and an option (itself a node) is offered only when its own condition holds. So an
action is reachable along a path root -> option -> ... -> node, and needs every condition on that path.

Conditions are judged against a FRESH visit (MapDialog.isConditionOk): no key items, quest/map flags unset
(get*Flag is false outright when the flag is unset, check*Flag honours `not`), every actor still present, and the
player holding some gold/life. A path whose conditions all hold on a fresh visit is FREE; anything else needs the
player to have done something first (a TASK).
"""
import ljson


def cond_fresh(c):
    """True/False for one condition object on a fresh visit, plus a short description of what it asks for."""
    notv = bool(c.get("not"))
    parts = []
    ok = True
    for k, v in c.items():
        if k == "not":
            continue
        if k == "item" and v:
            parts.append("item '%s'" % v)
            ok = ok and notv  # no key yet
        elif k in ("checkQuestFlag", "checkCharacterFlag", "checkMapFlag") and v:
            parts.append("%s%s %s" % ("NOT " if notv else "", k, v))
            ok = ok and notv
        elif k in ("getQuestFlag", "getCharacterFlag", "getMapFlag") and v:
            parts.append("%s %s %s %s" % (k, v.get("key"), v.get("op"), v.get("val")))
            ok = False  # unset flag -> false, `not` included
        elif k == "actorID" and v:
            parts.append("%sactor #%s present" % ("NOT " if notv else "", v))
            ok = ok and (not notv)
        elif k in ("hasGold", "hasShards") and v:
            parts.append("%s>=%s" % (k, v))
            # treated as satisfiable (a cost, not a lock) - reported in the description
        elif k == "hasLife" and v:
            parts.append("hasLife>%s" % v)
        elif k in ("hasBlessing", "colorIdentity") and v:
            parts.append("%s%s %s" % ("NOT " if notv else "", k, v))
            ok = ok and notv
        elif k == "hasMapReputation" and v not in (None, -2147483648):
            parts.append("mapRep>=%s" % v)
            ok = False
    return ok, " & ".join(parts)


def conds_fresh(conds):
    ok = True
    desc = []
    for c in conds or []:
        if not isinstance(c, dict):
            continue
        o, d = cond_fresh(c)
        ok = ok and o
        if d:
            desc.append(d)
    return ok, desc


def action_paths(dialog_json):
    """[(kind, target, path_conditions_list)] for deleteMapObject / activateMapObject actions.
    target -1 means the dialog's owner."""
    out = []
    data = ljson.loads(dialog_json) if isinstance(dialog_json, str) else dialog_json
    if data is None:
        return out, False
    if isinstance(data, dict):
        data = [data]

    def walk(node, conds):
        if not isinstance(node, dict):
            return
        c = list(conds) + [x for x in (node.get("condition") or []) if isinstance(x, dict)]
        for a in node.get("action") or []:
            if not isinstance(a, dict):
                continue
            if a.get("deleteMapObject"):
                out.append(("delete", int(a["deleteMapObject"]), c))
            if a.get("activateMapObject"):
                out.append(("activate", int(a["activateMapObject"]), c))
        for opt in node.get("options") or []:
            walk(opt, c)

    for root in data:
        walk(root, [])
    return out, True


def activates_fresh(dialog_json):
    """Does MapDialog.activate() show anything on a fresh visit (some root's condition holds)?"""
    data = ljson.loads(dialog_json) if isinstance(dialog_json, str) else dialog_json
    if not data:
        return False
    if isinstance(data, dict):
        data = [data]
    for root in data:
        if not isinstance(root, dict):
            continue
        ok, _ = conds_fresh(root.get("condition"))
        if not ok:
            continue
        # MapDialog.loadDialog: no options + no text + some action = an area trigger that shows nothing
        # (returns false, so the actor does not reset the player's position)
        if not (root.get("options") or []) and not (root.get("text") or "") and (root.get("action") or []):
            continue
        return True
    return False


FLAG_SETTERS = ("setQuestFlag", "setMapFlag", "setCharacterFlag")
FLAG_ADVANCERS = ("advanceQuestFlag", "advanceMapFlag", "advanceCharacterFlag")


def free_flag_sets(dialog_json):
    """flags (quest/map/character, by key) set or advanced by actions on FREE paths of this dialog."""
    data = ljson.loads(dialog_json) if isinstance(dialog_json, str) else dialog_json
    out = set()
    if not data:
        return out
    if isinstance(data, dict):
        data = [data]

    def walk(node, conds):
        if not isinstance(node, dict):
            return
        c = list(conds) + [x for x in (node.get("condition") or []) if isinstance(x, dict)]
        ok, _ = conds_fresh(c)
        if not ok:
            return
        for a in node.get("action") or []:
            if not isinstance(a, dict):
                continue
            for k in FLAG_SETTERS:
                v = a.get(k)
                if isinstance(v, dict) and v.get("key"):
                    out.add(v["key"])
            for k in FLAG_ADVANCERS:
                if a.get(k):
                    out.add(a[k])
        for opt in node.get("options") or []:
            walk(opt, c)

    for root in data:
        walk(root, [])
    return out


def activating_roots(dialog_json):
    """roots that show something on a fresh visit (see activates_fresh)."""
    data = ljson.loads(dialog_json) if isinstance(dialog_json, str) else dialog_json
    out = []
    if not data:
        return out
    if isinstance(data, dict):
        data = [data]
    for root in data:
        if not isinstance(root, dict):
            continue
        ok, _ = conds_fresh(root.get("condition"))
        if not ok:
            continue
        if not (root.get("options") or []) and not (root.get("text") or "") and (root.get("action") or []):
            continue
        out.append(root)
    return out


def root_disablers(root):
    """what can make this activating root stop showing: [('flag', key)] for check*Flag+not,
    [('actor', id)] for actorID present. Empty = unconditional (cannot be switched off)."""
    out = []
    for c in root.get("condition") or []:
        if not isinstance(c, dict):
            continue
        notv = bool(c.get("not"))
        for k in ("checkQuestFlag", "checkMapFlag", "checkCharacterFlag"):
            if c.get(k) and notv:
                out.append(("flag", c[k]))
        if c.get("actorID") and not notv:
            out.append(("actor", int(c["actorID"])))
        if c.get("item") and notv:
            out.append(("item", c["item"]))
    return out
