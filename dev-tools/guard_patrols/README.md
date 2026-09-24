# Chest patrols and crowded enemies (round 319)

The user: "296 chests have no guard - see if you can move existing enemies to patrol around these. Also, it seems
that some dungeons now have two enemies standing right next to each other ... remove one or give one patrol orders
if there are not a lot of enemies in the dungeon."

- `provenance.py <repo root> --out provenance.json` - labels every enemy placement with the round/tool that placed it
  (hand-authored vs rounds 279-287's guard tools), from git history.
- `audit.py <plane>` - per map: chests and whether a guard covers them (round 286's own definition,
  `booster_guards.audit(loot="treasure")`, plus existing patrol routes and standing enemies nearby), and clusters of
  standing fighters within 2 tiles (centre to centre). Writes audit.md/audit.json.
- `plan.py <plane> --provenance provenance.json [--exclude map#id]` - patrols (2-waypoint back-and-forth routes over
  walkable floor, written as `waypoint.tx` objects + a `waypoints` property like the maps already do), removals (only
  in maps with 8+ fighters on Hard, only a tool-added or duplicate enemy, never one whose removal leaves a booster or
  chest without its guard) and optional small relocations. Never touches bosses, legends, story/scripted or
  spawnRate-0 placements, dialog NPCs, hidden ambushers or enemies that already have a route. Writes plan.md/json.
- `apply.py <repo root> [--with-relocations]` - REQUIRED root; edits only the planned objects (ids from the map's
  `nextobjectid`), keeps each file's line endings, skips a map that changed since planning.
- `round319_plan.md` - what round 319 applied (with `--with-relocations`).
Afterwards: `dev-tools/waypoint_routes_qa.py` and `dev-tools/validate_plane_data.py`.
A patroller is presence, not the chest's registered guard (MapStage.assignLootGuards() matches authored positions).
