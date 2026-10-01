"""Round 388: every doodad picture of every land, read back from the plane's atlases, on one HTML page - the table the
user reviewed for cut/clipped pictures. A red border marks a picture whose opaque pixels reach the edge of its atlas
box (art cut by the packing); art cut BEFORE packing (a tile of a bigger object) has to be judged by eye.
python doodad_table.py [repo root] [out.html]   (default: this repo, <paths.OUT>/doodads_by_color.html)"""
import sys, json, base64, io, os, html
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import paths  # noqa: E402

world = os.path.join(paths.plane(sys.argv[1]) if len(sys.argv) > 1 else paths.plane(), "world")
out = sys.argv[2] if len(sys.argv) > 2 else os.path.join(paths.OUT, "doodads_by_color.html")
res_root = os.path.dirname(world)  # plane dir; atlas paths are "world/..."
LANDS = ["white", "blue", "black", "red", "green", "colorless", "player", "base"]
ALPHA = 24      # opaque threshold
EDGE_MIN = 2    # opaque pixels on one edge that count as a cut

def parse_atlas(path):
    regs = {}; page = None; cur = None
    for raw in open(path, encoding="utf-8"):
        line = raw.rstrip("\n")
        if not line.strip():
            page = None; continue
        if page is None:
            page = line.strip(); continue
        if ":" in line and (line.startswith(" ") or line.startswith("\t")):
            k, v = [s.strip() for s in line.split(":", 1)]
            if cur is not None:
                cur[k] = v
            continue
        if ":" in line:  # page header fields
            continue
        cur = {"page": page}; regs.setdefault(line.strip(), []).append(cur)
    return regs

sprites = json.load(open(os.path.join(world, "sprites", "map_sprites.json")))
default_atlas = sprites["textureAtlas"]
defs = {s["name"]: s for s in sprites["sprites"]}
atlases, pages = {}, {}

def atlas(p):
    if p not in atlases:
        atlases[p] = parse_atlas(os.path.join(res_root, p))
    return atlases[p]

def page_img(atlas_path, page):
    key = (atlas_path, page)
    if key not in pages:
        pages[key] = Image.open(os.path.join(res_root, os.path.dirname(atlas_path), page)).convert("RGBA")
    return pages[key]

def edges(im):
    w, h = im.size; a = im.getchannel("A").load(); hit = []
    for name, pts in (("top", [(x, 0) for x in range(w)]), ("bottom", [(x, h - 1) for x in range(w)]),
                      ("left", [(0, y) for y in range(h)]), ("right", [(w - 1, y) for y in range(h)])):
        n = sum(1 for p in pts if a[p] > ALPHA)
        if n >= EDGE_MIN:
            hit.append(f"{name} {n}px")
    return hit

def png64(im, zoom):
    im = im.resize((im.width * zoom, im.height * zoom), Image.NEAREST)
    b = io.BytesIO(); im.save(b, "PNG"); return base64.b64encode(b.getvalue()).decode()

rows_html, summary = [], []
total = flagged_total = 0
for land in LANDS:
    bp = os.path.join(world, "biomes", land + ".json")
    if not os.path.exists(bp):
        continue
    b = json.load(open(bp, encoding="utf-8-sig"))
    color = "#" + b.get("color", "777777")
    names = b.get("spriteNames", [])
    land_rows, land_flag, land_pics = [], 0, 0
    for n in names:
        d = defs.get(n)
        if d is None:
            land_rows.append(f"<tr><td>{n}</td><td colspan=3 class=bad>no entry in map_sprites.json</td></tr>"); continue
        ap = d.get("atlas", default_atlas)
        regs = atlas(ap).get(n, [])
        cells, nflag = [], 0
        for i, r in enumerate(regs):
            x, y = [int(v) for v in r["xy"].split(",")]
            w, h = [int(v) for v in r["size"].split(",")]
            im = page_img(ap, r["page"]).crop((x, y, x + w, y + h))
            hit = edges(im); nflag += bool(hit)
            zoom = max(2, 96 // max(w, h))
            cls = "pic cut" if hit else "pic"
            tip = html.escape(f"{n} #{i}  {os.path.basename(ap)} xy {x},{y} size {w}x{h}" + ("  touches box: " + ", ".join(hit) if hit else ""))
            cells.append(f"<figure class='{cls}' title='{tip}'><div class=bg style='background:{color}'>"
                         f"<img src='data:image/png;base64,{png64(im, zoom)}'></div>"
                         f"<figcaption>#{i}{'<br><b>' + '<br>'.join(hit) + '</b>' if hit else ''}</figcaption></figure>")
        total += len(regs); land_pics += len(regs); land_flag += nflag; flagged_total += nflag
        meta = f"{os.path.basename(ap)}<br>scale {d.get('scale', 1)} · layer {d.get('layer')}<br>density {d.get('density')}"
        if d.get("onStructures"):
            meta += "<br>on " + ", ".join(d["onStructures"])
        flag = f"<span class=badge>{nflag} at the box edge</span>" if nflag else ""
        land_rows.append(f"<tr{' class=hasCut' if nflag else ''}><td class=name>{n} {flag}</td><td class=meta>{meta}</td>"
                         f"<td>{len(regs)}</td><td class=pics>{''.join(cells) or '<span class=bad>no atlas region</span>'}</td></tr>")
    summary.append(f"<a href='#{land}' style='border-color:{color}'>{land.title()} · {len(names)} kinds · {land_pics} pics"
                   f"{' · <b>' + str(land_flag) + ' at the box edge</b>' if land_flag else ''}</a>")
    rows_html.append(f"<h2 id={land}><span class=sw style='background:{color}'></span>{land.title()}</h2>"
                     f"<table><tr><th>Doodad</th><th>Atlas</th><th>#</th><th>Pictures (hover for atlas position)</th></tr>{''.join(land_rows)}</table>")

page = f"""<!doctype html><html><head><meta charset=utf-8><meta name=viewport content='width=device-width,initial-scale=1'>
<title>TFR Doodads by Color</title><style>
:root{{--bg:#16181c;--fg:#e8e8e8;--mute:#9aa0a6;--line:#2c3036;--cut:#ff4d4d}}
body{{background:var(--bg);color:var(--fg);font:14px/1.4 system-ui,sans-serif;margin:0;padding:16px}}
h1{{margin:0 0 4px}} h2{{margin:28px 0 8px;display:flex;align-items:center;gap:10px}}
.sw{{width:22px;height:22px;border-radius:4px;display:inline-block;border:1px solid #0006}}
.nav{{display:flex;flex-wrap:wrap;gap:8px;margin:12px 0;position:sticky;top:0;background:var(--bg);padding:8px 0;z-index:2}}
.nav a{{color:var(--fg);text-decoration:none;border:2px solid;border-radius:6px;padding:4px 10px}}
label{{color:var(--mute)}}
table{{border-collapse:collapse;width:100%}} th,td{{border-bottom:1px solid var(--line);padding:6px;vertical-align:top;text-align:left}}
th{{color:var(--mute);font-weight:500}} .name{{font-weight:600;white-space:nowrap}} .meta{{color:var(--mute);font-size:12px;white-space:nowrap}}
.pics{{display:flex;flex-wrap:wrap;gap:8px}} figure{{margin:0;text-align:center;font-size:11px;color:var(--mute)}}
.bg{{padding:4px;border-radius:4px;border:2px solid transparent;line-height:0}}
.cut .bg{{border-color:var(--cut)}} .maybe .bg{{border-color:#ffb020;border-style:dashed}} .art .bg{{border-color:#4da3ff}} figcaption b{{font-weight:600}} .cut b{{color:var(--cut)}} .maybe b{{color:#ffb020}} .art b{{color:#4da3ff}}
.badge{{background:var(--cut);color:#fff;border-radius:10px;padding:1px 7px;font-size:11px;margin-left:4px}}
.bad{{color:var(--cut)}} img{{image-rendering:pixelated}}
body.only tr:not(.hasCut){{display:none}} body.only tr:first-child{{display:table-row}}
</style></head><body>
<h1>The Forsaken Realms · Doodads by Color</h1>
<div style='color:var(--mute)'>{total} pictures, {flagged_total} touching the edge of their atlas box (red border). Shown on the land's map color;
hover a picture for its atlas position.</div>
<div class=nav>{''.join(summary)}<label><input type=checkbox onchange="document.body.classList.toggle('only',this.checked)"> only rows with cuts</label></div>
{''.join(rows_html)}</body></html>"""
open(out, "w", encoding="utf-8").write(page)
print("pictures", total, "flagged", flagged_total, "bytes", len(page))
