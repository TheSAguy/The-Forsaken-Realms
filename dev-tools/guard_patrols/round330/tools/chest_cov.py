"""chest_cov.py - round 286's chest-guard coverage (booster_guards.audit(loot="treasure")) over every map."""
import glob, os, sys
sys.dont_write_bytecode = True
dt = sys.argv[1]
sys.path.insert(0, dt)
import booster_guards as bg, pixel_collision_qa as q
root = os.path.join(dt, "..", "forge-gui", "res", "adventure", "The Forsaken Realms", "maps", "map")
g = u = 0
for f in sorted(glob.glob(os.path.join(root, "**", "*.tmx"), recursive=True)):
    gg, uu, _rows = bg.audit(f, 3, loot="treasure")
    if os.path.basename(f) in q.ACCEPTED_UNREACHABLE:
        gg, uu = gg + uu, 0
    g += gg; u += uu
print("chests: %d guarded, %d unguarded (round 286's definition)" % (g, u))
