"""Round 305: a whirlpool doodad from the user's Wirlpool.png (the MV A1 whirlpool, one 48 px swirl tile), recoloured
into the in-game ocean's palette (its water sits at (21,99,151), the ocean's Base tile at (48,175,218)) and faded out
in a circle so it blends into the sea instead of showing as a square."""
import math

from PIL import Image

import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from paths import ART  # noqa: E402

SRC = os.path.join(ART, "Wirlpool.png")
OCEAN = (48, 175, 218)
BG = (18, 79, 137)


def whirlpool():
    tile = Image.open(SRC).convert("RGBA").crop((288, 288, 336, 336))
    gain = [OCEAN[c] / BG[c] for c in range(3)]
    out = Image.new("RGBA", tile.size, (0, 0, 0, 0))
    src, dst = tile.load(), out.load()
    cx = cy = 23.5
    for y in range(48):
        for x in range(48):
            r, g, b, a = src[x, y]
            d = math.hypot(x - cx, y - cy)
            fade = max(0.0, min(1.0, (23.0 - d) / 7.0))
            if fade <= 0:
                continue
            rr, gg, bb = min(255, int(r * gain[0])), min(255, int(g * gain[1])), min(255, int(b * gain[2]))
            dst[x, y] = (rr, gg, bb, int(a * fade))
    return out


HMM3_DIR = os.path.join(ART, "HMM3_whirlpool")   # round 391: AVXwhrl0-7.png, the HMM3 whirlpool's eight frames
FRAME_W = 48


def frames():
    """Round 391 (the user: "Let's animate the whirlpools"): the HMM3 whirlpool's eight frames for the ocean. HMM3 keys
    its sprites by palette - cyan (0,255,255) transparent; the frames are cut with ONE box (all eight share it, so the
    swirl does not jitter), halved to FRAME_W wide, and moved into this ocean's colours: each channel is OCEAN times the
    pixel's ratio to the frames' mean colour, so the swirl sits darker than the sea with light foam streaks and a dark
    eye (an offset from HMM3's rim water washed it out to a pale cloud - previewed). The oval fades out over its outer
    30%."""
    import numpy as np
    raw = []
    for i in range(8):
        a = np.asarray(Image.open(os.path.join(HMM3_DIR, "AVXwhrl%d.png" % i)).convert("RGB")).astype(int)
        key = (a[..., 0] == 0) & (a[..., 1] == 255) & (a[..., 2] == 255)
        rgba = np.zeros(a.shape[:2] + (4,), np.uint8)
        rgba[..., :3] = a
        rgba[..., 3] = np.where(key, 0, 255)
        raw.append(rgba)
    union = np.zeros(raw[0].shape[:2], bool)
    for f in raw:
        union |= f[..., 3] > 0
    ys, xs = np.where(union)
    box = (xs.min(), ys.min(), xs.max() + 1, ys.max() + 1)
    mean = np.concatenate([f[f[..., 3] > 0][:, :3] for f in raw]).astype(float).mean(axis=0)
    out = []
    for f in raw:
        img = Image.fromarray(f).crop(box)
        s = FRAME_W / img.width
        img = img.convert("RGBa").resize((FRAME_W, max(1, round(img.height * s))), Image.LANCZOS).convert("RGBA")
        a = np.asarray(img).astype(float)
        rgb = np.clip(np.array(OCEAN, float) * (np.maximum(a[..., :3], 1) / mean), 0, 255)
        h, w = a.shape[:2]
        yy, xx = np.mgrid[0:h, 0:w]
        d = np.hypot((xx - (w - 1) / 2) / (w / 2), (yy - (h - 1) / 2) / (h / 2))   # 1 at the oval's rim
        fade = np.clip((1.0 - d) / 0.3, 0, 1)
        alpha = a[..., 3] * fade
        out.append(Image.fromarray(np.dstack([rgb, alpha]).astype(np.uint8)))
    return out


if __name__ == "__main__":
    wp = whirlpool()
    wp.save("whirlpool_48.png")
    # preview on the in-game ocean tile
    import sys
    sys.path.insert(0, ".")
    from atlas import read_atlas
    r = read_atlas(r"C:\TFR\repo\forge-gui\res\adventure\common\world\tilesets\terrain.atlas")["Base"][0]
    page = Image.open(r["page"]).convert("RGBA")
    tile = page.crop((r["x"] + 16, r["y"] + 32, r["x"] + 32, r["y"] + 48)).resize((32, 32), Image.NEAREST)
    sea = Image.new("RGBA", (192, 128))
    for y in range(0, 128, 32):
        for x in range(0, 192, 32):
            sea.alpha_composite(tile, (x, y))
    big = wp.resize((64, 64), Image.NEAREST)   # drawn at 2 tiles (scale 2/3 of the 48 px picture)
    sea.alpha_composite(big, (32, 32))
    sea.alpha_composite(wp.resize((48, 48), Image.NEAREST), (120, 40))
    sea.resize((576, 384), Image.NEAREST).save("whirlpool_preview.png")
    print("ok")
