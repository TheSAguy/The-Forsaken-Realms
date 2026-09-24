"""Minimal libGDX .atlas reader: {name: [(page_path, x, y, w, h), ...]} (repeated names = variants, in order)."""
import os


def read_atlas(path):
    base = os.path.dirname(path)
    regions = {}
    page = None
    cur = None
    lines = open(path, encoding="utf-8").read().replace("\r\n", "\n").split("\n")
    i = 0
    expect_page = True
    while i < len(lines):
        line = lines[i]
        s = line.strip()
        i += 1
        if not s:
            expect_page = True
            cur = None
            continue
        if expect_page and not line.startswith((" ", "\t")) and ":" not in s:
            page = os.path.join(base, s)
            expect_page = False
            continue
        expect_page = False
        if ":" not in s and not line.startswith((" ", "\t")):
            cur = {"name": s, "page": page}
            regions.setdefault(s, []).append(cur)
            continue
        if ":" in s:
            k, v = [t.strip() for t in s.split(":", 1)]
            if cur is None:
                continue
            if k == "xy":
                cur["x"], cur["y"] = [int(t) for t in v.split(",")]
                cur.setdefault("w", 48)
                cur.setdefault("h", 64)
            elif k == "size":
                cur["w"], cur["h"] = [int(t) for t in v.split(",")]
    return regions
