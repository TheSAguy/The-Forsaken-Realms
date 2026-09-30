"""tmxobj.py - resolve Tiled objects the way libGDX's TmxMapLoader + MapStage see them (read-only helper).

Every object comes back with its template merged in: attributes (gid, width, height, name, type/class) and
properties (template first, the instance's own override by name). Coordinates stay in the .tmx frame
(top-down y); for a tile object (gid) the .tmx y is its BOTTOM edge, for a plain rectangle it is the TOP edge.
"""
import json
import os
import re
import xml.etree.ElementTree as ET

REPO = r"C:\TFR\repo"
MAP_ROOT = os.environ.get("TFR_MAP_ROOT") or os.path.join(REPO, "forge-gui", "res", "adventure", "The Forsaken Realms", "maps", "map")
GID_MASK = 0x1FFFFFFF

_TPL = {}


def _props(el):
    out = {}
    if el is None:
        return out
    pe = el.find("properties")
    if pe is None:
        return out
    for p in pe.findall("property"):
        v = p.get("value")
        if v is None:
            v = p.text or ""
        out[p.get("name")] = v
    return out


def load_template(path):
    path = os.path.normpath(path)
    if path in _TPL:
        return _TPL[path]
    try:
        root = ET.parse(path).getroot()
        obj = root.find("object")
        ts = root.find("tileset")
        tsrc = None
        if ts is not None and ts.get("source"):
            tsrc = (int(ts.get("firstgid", 1)), os.path.normpath(os.path.join(os.path.dirname(path), ts.get("source"))))
        rec = {"attrs": dict(obj.attrib) if obj is not None else {}, "props": _props(obj), "tileset": tsrc,
               "shape": None}
        if obj is not None:
            for s in ("polygon", "polyline", "ellipse", "point", "text"):
                if obj.find(s) is not None:
                    rec["shape"] = s
    except (OSError, ET.ParseError):
        rec = {"attrs": {}, "props": {}, "tileset": None, "shape": None}
    _TPL[path] = rec
    return rec


def parse_json_loose(s):
    if not s:
        return None
    try:
        return json.loads(s)
    except Exception:
        # the maps carry a few trailing commas / comments
        t = re.sub(r",\s*([\]}])", r"\1", s)
        try:
            return json.loads(t)
        except Exception:
            return None


def map_objects(tmx):
    """[dict] one per <object> in document order (MapStage walks layers then objects in that order).

    keys: id, layer, layer_index, index_in_layer, x, y, w, h, gid, type, name, template (basename), props,
          visible, is_tile (has a gid), shape
    """
    root = ET.parse(tmx).getroot()
    base = os.path.dirname(os.path.abspath(tmx))
    out = []
    li = 0
    for layer in list(root):
        if layer.tag != "objectgroup":
            if layer.tag == "layer":
                li += 1
            continue
        li += 1
        for k, o in enumerate(layer.findall("object")):
            tpl_path = o.get("template")
            tpl = load_template(os.path.join(base, tpl_path)) if tpl_path else {"attrs": {}, "props": {}, "tileset": None, "shape": None}
            attrs = dict(tpl["attrs"])
            attrs.update(o.attrib)
            props = dict(tpl["props"])
            props.update(_props(o))
            typ = o.get("type") or o.get("class") or tpl["attrs"].get("type") or tpl["attrs"].get("class")
            if "type" in props and props["type"]:
                typ = props["type"]
            gid = attrs.get("gid")
            shape = tpl["shape"]
            for s in ("polygon", "polyline", "ellipse", "point", "text"):
                if o.find(s) is not None:
                    shape = s
            try:
                x = float(attrs.get("x", 0))
                y = float(attrs.get("y", 0))
            except ValueError:
                x = y = 0.0
            w = float(attrs.get("width", 0) or 0)
            h = float(attrs.get("height", 0) or 0)
            out.append({
                "id": int(o.get("id", 0)), "layer": layer.get("name"), "layer_index": li, "index_in_layer": k,
                "x": x, "y": y, "w": w, "h": h, "gid": int(gid) if gid else None, "type": typ,
                "name": attrs.get("name") or "", "template": os.path.basename(tpl_path) if tpl_path else "",
                "props": props, "visible": o.get("visible", "1") != "0", "is_tile": bool(gid), "shape": shape,
                "tpl_tileset": tpl["tileset"] if (tpl_path and not o.get("gid")) else None,
            })
    return out


def bottom_left(o):
    """(x, y_bottom) in the .tmx top-down frame: the corner MapStage stores as the actor position (libGDX's
    bottom-left), so distances between two of these equal the runtime's pos() distances."""
    if o["is_tile"]:
        return o["x"], o["y"]
    return o["x"], o["y"] + o["h"]


def all_maps():
    out = []
    for dp, dn, fn in os.walk(MAP_ROOT):
        for f in fn:
            if f.endswith(".tmx"):
                out.append(os.path.join(dp, f))
    return sorted(out)


def rel(tmx):
    return os.path.relpath(tmx, MAP_ROOT).replace("\\", "/")
