"""Where the world-art pipeline reads and writes (round 309: moved into the repo from the round-303 scratchpad).
ART  - the user's source sheets (outside the repo; credited in standalone-packaging/CREDITS.md)
OUT  - previews and catalogs (outside the repo): WORLD_ART_OUT, default C:\\TFR\\art-staging\\world-art
HERE - this folder: the design (spec.py), the cut boxes (boxes/), the converted MV autotiles (autotiles/)"""
import os

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(os.path.dirname(HERE))
# Round 388: the user's Terrain folder moved under "New Art" (the old path stays as a fallback)
ART = next((p for p in (r"C:\Users\User\Pictures\Screenshots\New Art\Terrain",
                        r"C:\Users\User\Pictures\Screenshots\Terrain") if os.path.isdir(p)),
           r"C:\Users\User\Pictures\Screenshots\New Art\Terrain")
OUT = os.environ.get("WORLD_ART_OUT", r"C:\TFR\art-staging\world-art")
os.makedirs(OUT, exist_ok=True)


def plane(root=REPO):
    p = os.path.join(root, "forge-gui", "res", "adventure", "The Forsaken Realms")
    if not os.path.isdir(os.path.join(p, "world", "biomes")):
        raise SystemExit("not a TFR repo root: " + root)
    return p
