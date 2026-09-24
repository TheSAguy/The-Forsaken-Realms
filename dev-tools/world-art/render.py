"""Round 303: BiomeTexture.drawPixmapOn ported (copied from round 300's green_preview.py) for preview scenes."""

# --- BiomeTexture.drawPixmapOn, ported --------------------------------------------------------------------------
BIG = {0b111_111_111: (1, 2), 0b111_111_000: (1, 3), 0b000_111_111: (1, 1), 0b011_011_011: (0, 2),
       0b110_110_110: (2, 2), 0b010_111_010: (2, 0), 0b001_011_111: (0, 1), 0b100_110_111: (2, 1),
       0b111_011_001: (0, 3), 0b111_110_100: (2, 3)}
SMALL = {"LeftTopEdge00": 12, "LeftEdge00": 24, "TopEdge00": 14, "InnerTopLeftEdge": 4, "Center00": 26,
         "RightTopEdge10": 17, "RightEdge10": 29, "TopEdge10": 15, "InnerTopRightEdge": 5, "Center10": 27,
         "LeftBottomEdge01": 42, "BottomEdge01": 44, "LeftEdge01": 30, "InnerBottomLeftEdge": 10, "Center01": 32,
         "RightBottomEdge11": 47, "BottomEdge11": 45, "RightEdge11": 35, "InnerBottomRightEdge": 11, "Center11": 33}


def quad(n):
    tl = {0: "LeftTopEdge00", 0b100_000_000: "LeftTopEdge00", 0b010_000_000: "LeftEdge00", 0b110_000_000: "LeftEdge00",
          0b000_100_000: "TopEdge00", 0b100_100_000: "TopEdge00", 0b010_100_000: "InnerTopLeftEdge",
          0b110_100_000: "Center00"}[n & 0b110_100_000]
    tr = {0: "RightTopEdge10", 0b001_000_000: "RightTopEdge10", 0b011_000_000: "RightEdge10", 0b010_000_000: "RightEdge10",
          0b001_001_000: "TopEdge10", 0b000_001_000: "TopEdge10", 0b010_001_000: "InnerTopRightEdge",
          0b011_001_000: "Center10"}[n & 0b011_001_000]
    bl = {0: "LeftBottomEdge01", 0b000_000_100: "LeftBottomEdge01", 0b000_100_100: "BottomEdge01",
          0b000_100_000: "BottomEdge01", 0b000_000_110: "LeftEdge01", 0b000_000_010: "LeftEdge01",
          0b000_100_010: "InnerBottomLeftEdge", 0b000_100_110: "Center01"}[n & 0b000_100_110]
    br = {0: "RightBottomEdge11", 0b000_000_001: "RightBottomEdge11", 0b000_001_001: "BottomEdge11",
          0b000_001_000: "BottomEdge11", 0b000_000_011: "RightEdge11", 0b000_000_010: "RightEdge11",
          0b000_001_010: "InnerBottomRightEdge", 0b000_001_011: "Center11"}[n & 0b000_001_011]
    return tl, tr, bl, br


def draw_tile(dst, auto, tile, n, px, py):
    if n in BIG:
        bx, by = BIG[n]
        dst.alpha_composite(auto.crop((bx * tile, by * tile, bx * tile + tile, by * tile + tile)), (px, py))
        return
    h = tile // 2
    for name, (ox, oy) in zip(quad(n), ((0, 0), (h, 0), (0, h), (h, h))):
        idx = SMALL[name]
        sx, sy = (idx % 6) * h, (idx // 6) * h
        dst.alpha_composite(auto.crop((sx, sy, sx + h, sy + h)), (px + ox, py + oy))


def layer_mask(grid, x, y, value):
    n = 0
    for dy in (-1, 0, 1):
        for dx in (-1, 0, 1):
            gx, gy = x + dx, y + dy
            inside = 0 <= gy < len(grid) and 0 <= gx < len(grid[0])
            present = inside and value in grid[gy][gx]
            n = (n << 1) | (1 if (present or not inside) else 0)
    return n


