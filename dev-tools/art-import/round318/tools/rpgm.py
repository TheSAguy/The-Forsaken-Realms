"""rpgm.py - slice RPG Maker MV/MZ character sheets (the whole "New Enemy Art" folder is in this format).

RPG Maker layout: a CHARACTER is a 3-column x 4-row block of frames.
  rows    = facing Down, Left, Right, Up          (rows="dirs")
  columns = step A, NEUTRAL (the standing pose), step B; the engine walks 0,1,2,1
A normal sheet holds 4 x 2 characters (12 x 8 frames); a "$" sheet holds ONE character (3 x 4 frames); "!" only
means "no 6-px lift, no bush transparency". Some sheets in this pack are not direction sheets: their four rows are
four COLOR VARIANTS of one front-facing pose with a 3-frame idle (rows="variants"), or states / poses of an object.
"""
import os
from PIL import Image

DIRS = ["Down", "Left", "Right", "Up"]


class Sheet:
    def __init__(self, path, blocks=(4, 2), rows="dirs"):
        self.path = path
        self.name = os.path.basename(path)
        self.img = Image.open(path).convert("RGBA")
        self.blocks = blocks
        self.rows = rows
        bx, by = blocks
        W, H = self.img.size
        self.fw, self.fh = W // (3 * bx), H // (4 * by)

    def frame(self, block, row, col):
        """block is 0-based, numbered left->right then top->bottom (the report numbers them 1-8)."""
        bx, _ = self.blocks
        cx, cy = block % bx, block // bx
        x = (cx * 3 + col) * self.fw
        y = (cy * 4 + row) * self.fh
        return self.img.crop((x, y, x + self.fw, y + self.fh))

    def block_empty(self, block):
        for r in range(4):
            for c in range(3):
                if self.frame(block, r, c).getchannel("A").getbbox():
                    return False
        return True

    def variants(self):
        """(label, block, row) per creature variant present on the sheet."""
        bx, by = self.blocks
        out = []
        if self.rows == "variants":
            for r in range(4):
                if any(self.frame(0, r, c).getchannel("A").getbbox() for c in range(3)):
                    out.append(("v%d" % (r + 1), 0, r))
            return out
        for b in range(bx * by):
            if not self.block_empty(b):
                out.append(("v%d" % (b + 1), b, None))
        return out


def flattened(img):
    """True when the sheet has no real transparency (an RGB export flattened onto a background)."""
    lo, hi = img.getchannel("A").getextrema()
    return lo == 255
