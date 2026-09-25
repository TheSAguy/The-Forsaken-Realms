"""
tmx.py - a small Tiled map reader/writer/renderer for the place maps (round 342).

Map(path) -> .tree (ElementTree), .width/.height, .tilesets [TilesetInfo by firstgid], .layers {name: Layer}.
Layer.cells is a flat list of gids (flip bits kept); write() re-encodes them (base64 + zlib, as Tiled does).
Map.render(scale) draws every tile layer in file order to a PIL image - a "what the player sees" check.
Map.absolutize() turns every tileset reference into an absolute path (a template saved outside the plane).
"""
import base64
import os
import struct
import xml.etree.ElementTree as ET
import zlib

from PIL import Image

FLIP_MASK = 0x1FFFFFFF


class TilesetInfo:
    def __init__(self, name, firstgid, tilecount, columns, tw, th, image_path, source):
        self.name, self.firstgid, self.tilecount, self.columns = name, firstgid, tilecount, columns
        self.tw, self.th, self.image_path, self.source = tw, th, image_path, source
        self._img = None
        self._cache = {}

    def image(self):
        if self._img is None:
            self._img = Image.open(self.image_path).convert("RGBA")
        return self._img

    def tile(self, local_id):
        if local_id not in self._cache:
            x, y = (local_id % self.columns) * self.tw, (local_id // self.columns) * self.th
            self._cache[local_id] = self.image().crop((x, y, x + self.tw, y + self.th))
        return self._cache[local_id]


def _tileset_from_element(el, base_dir, firstgid, source=None):
    img = el.find("image")
    image_path = os.path.normpath(os.path.join(base_dir, img.get("source"))) if img is not None else None
    return TilesetInfo(el.get("name"), firstgid, int(el.get("tilecount", 0)), int(el.get("columns", 1)),
                       int(el.get("tilewidth", 16)), int(el.get("tileheight", 16)), image_path, source)


class Layer:
    def __init__(self, el, width, height):
        self.el = el
        self.name = el.get("name")
        self.width, self.height = width, height
        data = el.find("data")
        assert data.get("encoding") == "base64" and data.get("compression") == "zlib", (self.name, data.attrib)
        raw = zlib.decompress(base64.b64decode(data.text.strip()))
        self.cells = list(struct.unpack("<%dI" % (len(raw) // 4), raw))

    def get(self, x, y):
        return self.cells[y * self.width + x]

    def set(self, x, y, gid):
        self.cells[y * self.width + x] = gid

    def encode(self):
        raw = struct.pack("<%dI" % len(self.cells), *self.cells)
        self.el.find("data").text = "\n   " + base64.b64encode(zlib.compress(raw, 9)).decode("ascii") + "\n  "


class Map:
    def __init__(self, path):
        self.path = path
        self.tree = ET.parse(path)
        self.root = self.tree.getroot()
        self.width, self.height = int(self.root.get("width")), int(self.root.get("height"))
        self.tw, self.th = int(self.root.get("tilewidth")), int(self.root.get("tileheight"))
        base = os.path.dirname(os.path.abspath(path))
        self.tilesets = []
        for el in self.root.findall("tileset"):
            firstgid = int(el.get("firstgid"))
            if el.get("source"):
                tsx = os.path.normpath(os.path.join(base, el.get("source")))
                tel = ET.parse(tsx).getroot()
                self.tilesets.append(_tileset_from_element(tel, os.path.dirname(tsx), firstgid, el.get("source")))
            else:
                self.tilesets.append(_tileset_from_element(el, base, firstgid))
        self.tilesets.sort(key=lambda t: t.firstgid)
        self.layers = {}
        self.layer_order = []
        for el in self.root.findall("layer"):
            layer = Layer(el, int(el.get("width")), int(el.get("height")))
            self.layers[layer.name] = layer
            self.layer_order.append(layer.name)

    def tileset_for(self, gid):
        gid &= FLIP_MASK
        found = None
        for ts in self.tilesets:
            if ts.firstgid <= gid:
                found = ts
        return found

    def describe(self, gid):
        ts = self.tileset_for(gid)
        return "%s:%d" % (ts.name, (gid & FLIP_MASK) - ts.firstgid) if ts else "gid %d" % gid

    def add_external_tileset(self, source_path):
        """Appends an external tileset reference after the last one; returns its firstgid."""
        last = self.tilesets[-1]
        firstgid = last.firstgid + last.tilecount
        el = ET.Element("tileset", firstgid=str(firstgid), source=source_path)
        el.tail = "\n "
        idx = list(self.root).index(self.root.findall("tileset")[-1]) + 1
        self.root.insert(idx, el)
        base = os.path.dirname(os.path.abspath(self.path))
        tsx = source_path if os.path.isabs(source_path) else os.path.normpath(os.path.join(base, source_path))
        tel = ET.parse(tsx).getroot()
        self.tilesets.append(_tileset_from_element(tel, os.path.dirname(tsx), firstgid, source_path))
        return firstgid

    def absolutize(self):
        """Every file reference - tileset sources, an embedded tileset's image, and the objects' templates (the
        shops, NPCs and entries are .tx templates from maps/obj/) - as an absolute path with forward slashes."""
        base = os.path.dirname(os.path.abspath(self.path))

        def absolute(rel):
            return os.path.abspath(os.path.join(base, rel)).replace(os.sep, "/")

        for el in self.root.findall("tileset"):
            if el.get("source"):
                el.set("source", absolute(el.get("source")))
            else:
                img = el.find("image")
                if img is not None:
                    img.set("source", absolute(img.get("source")))
        for il in self.root.findall("imagelayer"):
            img = il.find("image")
            if img is not None and img.get("source"):
                img.set("source", absolute(img.get("source")))
        for obj in self.root.iter("object"):
            if obj.get("template"):
                obj.set("template", absolute(obj.get("template")))

    def references(self):
        """Every file the map refers to (tileset sources, images, object templates), as written."""
        refs = []
        for el in self.root.findall("tileset"):
            refs.append(el.get("source") or el.find("image").get("source"))
        for il in self.root.findall("imagelayer"):
            img = il.find("image")
            if img is not None and img.get("source"):
                refs.append(img.get("source"))
        for obj in self.root.iter("object"):
            if obj.get("template"):
                refs.append(obj.get("template"))
        return refs

    def missing(self):
        """The references that do not resolve to a file, relative to the map's folder."""
        base = os.path.dirname(os.path.abspath(self.path))
        out = []
        for ref in self.references():
            path = ref if os.path.isabs(ref) else os.path.join(base, ref)
            if not os.path.exists(path):
                out.append(ref)
        return sorted(set(out))

    def render(self, scale=1, only=None):
        img = Image.new("RGBA", (self.width * self.tw, self.height * self.th), (0, 0, 0, 255))
        for name in self.layer_order:
            if only and name not in only:
                continue
            layer = self.layers[name]
            for y in range(self.height):
                for x in range(self.width):
                    gid = layer.get(x, y)
                    if gid == 0:
                        continue
                    ts = self.tileset_for(gid)
                    if ts is None or ts.image_path is None:
                        continue
                    tile = ts.tile((gid & FLIP_MASK) - ts.firstgid)
                    if gid & 0x80000000:
                        tile = tile.transpose(Image.FLIP_LEFT_RIGHT)
                    if gid & 0x40000000:
                        tile = tile.transpose(Image.FLIP_TOP_BOTTOM)
                    img.alpha_composite(tile, (x * self.tw, y * self.th))
        if scale != 1:
            img = img.resize((img.width * scale, img.height * scale), Image.NEAREST)
        return img

    def write(self, path):
        for layer in self.layers.values():
            layer.encode()
        self.tree.write(path, encoding="UTF-8", xml_declaration=True)
        with open(path, "ab") as f:  # Tiled ends its files with a newline; ElementTree does not
            f.write(b"\n")
