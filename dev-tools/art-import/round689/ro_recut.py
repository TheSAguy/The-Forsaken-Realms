"""ro_recut.py - round 689: re-cut six round-179 Ragnarok Online enemies from their source sheets.

Round 179's art_convert.py misread these six sheets' rows (a Walk built from the attack row, or a front view running
into the back view) and centered every frame on its own bounding box, so the body slid sideways whenever a weapon
swung and dropped whenever a foot lifted: the walks "jumped to a bigger drawing mid-step". Here the clips are named
by hand (SPECS: (row, index) on the sheet, rows numbered top to bottom as rows() finds them) and every frame is
anchored on the BODY: Idle/Walk frames by the head and shoulders (top 45% of the clip's first frame), Attack/Hit/
Death by the whole figure, vertical offsets as drawn on the sheet within a row; a clip's first frame is registered
to Idle frame 0. Shape overlap, color as a tie-breaker (RO re-shades every frame). The cell is the union of every
placed frame, so nothing is shrunk (179 LANCZOS-shrank frames larger than its cell).

Facing: each sprite keeps the orientation of its CURRENT atlas (Idle frame 0 compared unflipped and flipped) - so
Anvilhorn Minotaur keeps round 681's mirror and the others round 179's flip. The Avatar region is copied as is.
Mossmane Shambler walks with its winged idle (the user's pick: the RO walk row has no wings, so they popped on and off).

Sources: Art-Library/Units/PC _ Computer - Ragnarok Online - <sheet>.png (the user's library, outside the repo).

usage: python ro_recut.py [--write] [names...]     (dry run prints cells and anchoring notes)
"""
import os, sys
from collections import deque
import numpy as np
from PIL import Image

TFR = os.environ.get("TFR_REPO", "C:/TFR/repo")
D = TFR + "/forge-gui/res/adventure/The Forsaken Realms/sprites/enemy/tfr/"
SCORE_MIN = 0.3
WALK_FROM = {"mossmane_shambler": "Idle"}

LIB="C:/Users/User/Claude_Code/Bannerloard/Art-Library/Units/PC _ Computer - Ragnarok Online - "
SHEETS={"rattling_bones":"Enemies - Skeleton (Weak).png","cracked_shieldbone":"Enemies - Skeleton (Weak, Soldier).png",
 "barrow_bonebow":"Enemies - Skeleton (Archer).png","orcling_rascal":"Enemies - Orc Baby.png",
 "anvilhorn_minotaur":"Enemies - Nightmare Minorous.png","mossmane_shambler":"Homunculus - Vanilmirth (Alternate, Evolved).png"}
def load(n):
    im=Image.open(LIB+SHEETS[n]).convert("RGBA")
    a=np.array(im)
    # key opaque corner color
    keys={tuple(a[y,x,:3]) for y,x in [(0,0),(0,-1),(-1,0),(-1,-1)] if a[y,x,3]>=24}
    for k in keys:
        m=(a[:,:,:3]==k).all(2)&(a[:,:,3]>=24); a[m]=0
    return a
def comps(a,join=3):
    m=a[:,:,3]>=24
    d=m.copy()
    for dy in range(-join,join+1):
        for dx in range(-join,join+1):
            d|=np.roll(np.roll(m,dy,0),dx,1)
    H,W=m.shape; lab=np.zeros((H,W),np.int32); n=0; boxes=[]
    ys,xs=np.nonzero(d)
    for y0,x0 in zip(ys,xs):
        if lab[y0,x0]: continue
        n+=1; q=deque([(y0,x0)]); lab[y0,x0]=n
        while q:
            y,x=q.popleft()
            for yy,xx in ((y+1,x),(y-1,x),(y,x+1),(y,x-1)):
                if 0<=yy<H and 0<=xx<W and d[yy,xx] and not lab[yy,xx]:
                    lab[yy,xx]=n;q.append((yy,xx))
    out=[]
    for i in range(1,n+1):
        mm=(lab==i)&m
        yy,xx=np.nonzero(mm)
        if len(yy)<30: continue
        out.append((xx.min(),yy.min(),xx.max()+1,yy.max()+1,len(yy)))
    return out
def rows(cs):
    cs=sorted(cs,key=lambda c:(c[1]+c[3])/2)
    rs=[]
    for c in cs:
        cy=(c[1]+c[3])/2
        for r in rs:
            if r["y0"]-2<=cy<=r["y1"]+2: r["f"].append(c); r["y0"]=min(r["y0"],c[1]); r["y1"]=max(r["y1"],c[3]); break
        else: rs.append({"y0":c[1],"y1":c[3],"f":[c]})
    for r in rs: r["f"].sort()
    return rs


# clip -> list of (row, index) on the source sheet (rows as printed by showrows.py). Front views only.
R = lambda r, *ix: [(r, i) for i in ix]
SPECS = {
 "rattling_bones":     {"Idle": R(0,*range(8)), "Walk": R(0,*range(8)), "Attack": R(4,*range(8)),
                        "Hit": R(8,0), "Death": R(8,1,2,3)+R(10,0)},
 "cracked_shieldbone": {"Idle": R(0,*range(8)), "Walk": R(0,*range(8)), "Attack": R(4,*range(8)),
                        "Hit": R(8,0), "Death": R(8,1,2,3)+R(10,0)},
 # row 3 merges standing-back (bottom 238) and walking (bottom ~355): walking front = sorted by x, bottom > 300
 "barrow_bonebow":     {"Idle": R(0,*range(6)), "Walk": "WALK_BONEBOW", "Attack": R(4,*range(8)),
                        "Hit": R(8,0), "Death": R(8,1,2)},
 "orcling_rascal":     {"Idle": R(0,0,1,2,3), "Walk": R(1,0,1,2,3), "Attack": R(2,*range(6)),
                        "Hit": R(4,0), "Death": R(4,0,2)},
 "anvilhorn_minotaur": {"Idle": R(0,*range(8)), "Walk": R(4,*range(8)), "Attack": R(8,*range(7)),
                        "Hit": R(12,0), "Death": R(12,0,1)},
 "mossmane_shambler":  {"Idle": R(0,*range(7)), "Walk": R(2,*range(7)), "Attack": R(5,*range(9)),
                        "Hit": R(7,0), "Death": R(7,*range(7))},
}


def frame(a, box):
    x0, y0, x1, y1 = box[:4]
    f = a[y0:y1, x0:x1].copy()
    return f

def reg(T, F, base, frac, axis_y=True, rng=(30, 18), region="top"):
    """Best offset of F's box relative to T's box so the template region of T matches F.
    base = (dx, dy) starting guess; returns (dx, dy, score fraction)."""
    th = T.shape[0]
    if region == "top":
        ys = slice(0, max(4, int(th * frac)))
    elif region == "all":
        ys = slice(0, th)
    else:
        ys = slice(th - max(4, int(th * frac)), th)
    Tm = T[ys, :, 3] >= 128
    ty, tx = np.nonzero(Tm)
    ty = ty + ys.start
    if len(ty) == 0:
        return base[0], base[1], 0
    tc = T[ty, tx, :3].astype(int)
    best = (-1, base[0], base[1])
    H, W = F.shape[:2]
    dys = range(base[1] - rng[1], base[1] + rng[1] + 1) if axis_y else [base[1]]
    for dy in dys:
        fy = ty - dy
        for dx in range(base[0] - rng[0], base[0] + rng[0] + 1):
            fx = tx - dx
            ok = (fy >= 0) & (fy < H) & (fx >= 0) & (fx < W)
            if not ok.any():
                continue
            fp = F[fy[ok], fx[ok]]
            # shape overlap, half credit for a color mismatch (RO re-shades every frame)
            op = fp[:, 3] >= 128
            col = np.abs(fp[:, :3].astype(int) - tc[ok]).sum(1) < 90
            s = int(op.sum()) + int((op & col).sum())
            if s > best[0] or (s == best[0] and abs(dx - base[0]) + abs(dy - base[1]) < abs(best[1] - base[0]) + abs(best[2] - base[1])):
                best = (s, dx, dy)
    return best[1], best[2], best[0] / (2 * len(ty))

_ROWS = {}

def rows_of(n):
    if n not in _ROWS:
        _ROWS[n] = rows(comps(load(n)))
    return _ROWS[n]


def boxes_for(n, spec_clip):
    rs = rows_of(n)
    if spec_clip == "WALK_BONEBOW":
        fs = sorted([c for c in rs[3]["f"] if c[3] > 300 and c[3] - c[1] > 60], key=lambda c: c[0])
        return [(3, c) for c in fs[:4]]
    return [(r, rs[r]["f"][i]) for r, i in spec_clip]

def build(n, spec=None, walk_from=None):
    """pos[(row, box)] = the frame's top-left in Idle-0 coordinates."""
    spec = dict(spec or SPECS[n])
    a = load(n)
    clips = {k: boxes_for(n, v) for k, v in spec.items()}
    if walk_from:
        clips["Walk"] = clips[walk_from]
    ir, rb = clips["Idle"][0]
    R0 = frame(a, rb)
    pos, notes = {}, []
    for k, fl in clips.items():
        body = k in ("Idle", "Walk")
        first = None
        for (r, box) in fl:
            key = (r, tuple(box[:4]))
            F = frame(a, box)
            fw, fh = box[2] - box[0], box[3] - box[1]
            if first is None:
                if key in pos:
                    first = (F, r, box, pos[key]); continue
                if r == ir:
                    dy = box[1] - rb[1]
                    dx, _, sc = reg(R0, F, ((R0.shape[1] - fw) // 2, dy), 0.45, axis_y=False)
                else:
                    dx, dy, sc = reg(R0, F, ((R0.shape[1] - fw) // 2, R0.shape[0] - fh), 0.45, rng=(30, 24))
                    if sc < SCORE_MIN:
                        dx, dy = (R0.shape[1] - fw) // 2, R0.shape[0] - fh
                        notes.append("%s first frame: centered (score %.2f)" % (k, sc))
                pos[key] = (dx, dy)
                first = (F, r, box, (dx, dy))
                continue
            if key in pos:
                continue
            F0, r0, b0, (x0, y0) = first
            w0, h0 = b0[2] - b0[0], b0[3] - b0[1]
            dy = box[1] - b0[1] if r == r0 else h0 - fh
            dx, _, sc = reg(F0, F, ((w0 - fw) // 2, dy), 0.45 if body else 0.3, axis_y=False,
                            region="top" if body else "all")
            if sc < SCORE_MIN:
                dx = (w0 - fw) // 2
                notes.append("%s frame %d: centered (score %.2f)" % (k, fl.index((r, box)), sc))
            pos[key] = (x0 + dx, y0 + dy)
    xs = [x for x, y in pos.values()]
    ys = [y for x, y in pos.values()]
    xe = [x + (k[1][2] - k[1][0]) for k, (x, y) in pos.items()]
    ye = [y + (k[1][3] - k[1][1]) for k, (x, y) in pos.items()]
    ux, uy = min(xs) - 1, min(ys) - 1
    return a, clips, pos, (ux, uy, max(xe) - ux + 1, max(ye) - uy + 1), notes

def cells(n, flip, **kw):
    a, clips, pos, (ux, uy, cw, ch), notes = build(n, **kw)
    out = {}
    for k, fl in clips.items():
        out[k] = []
        for (r, b) in fl:
            x, y = pos[(r, tuple(b[:4]))]
            c = Image.new("RGBA", (cw, ch))
            c.paste(Image.fromarray(frame(a, b)), (x - ux, y - uy))
            if flip:
                c = c.transpose(Image.FLIP_LEFT_RIGHT)
            out[k].append(c)
    return out, (cw, ch), notes


# ------------------------------------------------------------------ atlas i/o
def read_atlas(name):
    lines = open(D + name + ".atlas", encoding="utf-8").read().splitlines()
    regs, cur = [], None
    for l in lines[5:]:
        if l.strip() and not l.startswith(" "):
            cur = {"name": l.strip()}
            regs.append(cur)
        elif cur is not None and ":" in l:
            k, v = l.strip().split(":", 1)
            cur[k] = [int(x) for x in v.split(",")]
    return regs


def old_clip(name, clip):
    img = Image.open(D + name + ".png").convert("RGBA")
    return [img.crop((r["xy"][0], r["xy"][1], r["xy"][0] + r["size"][0], r["xy"][1] + r["size"][1]))
            for r in read_atlas(name) if r["name"] == clip]


def detect_flip(name, new):
    ob = old_clip(name, "Idle")[0]
    ob = ob.crop(ob.getbbox())
    best = None
    for fl in (False, True):
        nf = new["Idle"][0].transpose(Image.FLIP_LEFT_RIGHT) if fl else new["Idle"][0]
        nb = nf.crop(nf.getbbox())
        if nb.size != ob.size:
            continue
        d = int(np.abs(np.array(nb).astype(int) - np.array(ob).astype(int)).sum())
        if best is None or d < best[0]:
            best = (d, fl)
    assert best is not None and best[0] == 0, "%s: the new Idle 0 matches neither facing of the current one" % name
    return best[1]


def write(name):
    new, (cw, ch), notes = cells(name, False, walk_from=WALK_FROM.get(name))
    if detect_flip(name, new):
        new = {k: [c.transpose(Image.FLIP_LEFT_RIGHT) for c in v] for k, v in new.items()}
    av = old_clip(name, "Avatar")[0]
    side = av.size[0]
    order = [k for k in ("Idle", "Walk", "Attack", "Hit", "Death") if new.get(k)]
    ncol = max(len(new[k]) for k in order)
    sheet = Image.new("RGBA", (max(ncol * cw, side), side + len(order) * ch))
    sheet.paste(av, (0, 0))
    lines = [name + ".png", "size: %d,%d" % sheet.size, "format: RGBA8888", "filter: Nearest,Nearest", "repeat: none",
             "Avatar", "  xy: 0, 0", "  size: %d, %d" % (side, side)]
    for ri, k in enumerate(order):
        y = side + ri * ch
        for ci, c in enumerate(new[k]):
            sheet.paste(c, (ci * cw, y))
            lines += [k, "  xy: %d, %d" % (ci * cw, y), "  size: %d, %d" % (cw, ch)]
    sheet.save(D + name + ".png")
    with open(D + name + ".atlas", "w", newline="\n") as fh:
        fh.write("\n".join(lines) + "\n")
    return (cw, ch), {k: len(new[k]) for k in order}, notes


if __name__ == "__main__":
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    for n in args or list(SPECS):
        if "--write" in sys.argv:
            print(n, *write(n))
        else:
            new, cell, notes = cells(n, False, walk_from=WALK_FROM.get(n))
            print(n, cell, {k: len(v) for k, v in new.items()}, notes)
