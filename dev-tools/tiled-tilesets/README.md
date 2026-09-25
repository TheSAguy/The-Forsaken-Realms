# tiled-tilesets - Tiled tilesets for the place maps (round 341)

`make_tilesets.py` writes 16 px Tiled tilesets into the plane's `maps/tileset/` folder, so a place map - the Player
Capitol first - can be painted with the player land's own look and with the RPG Maker MV sheets. Run it from the repo
root; it needs Pillow and reads `dev-tools/world-art/render.py` (the game's autotile rule) beside it.

    python dev-tools/tiled-tilesets/make_tilesets.py              # writes every tileset into maps/tileset/
    python dev-tools/tiled-tilesets/make_tilesets.py --preview    # also preview_*.png (each terrain set on a test shape)
    python dev-tools/tiled-tilesets/make_tilesets.py --only player,worldb --out C:/somewhere

## Why 16 px

`MapStage` sizes a place map as width x tilewidth in world units and the player sprite is 16 units, so a place map's
tiles are 16 px whatever the source art was. The overworld's 32 px HD look is a renderer trick (32 texels per 16-unit
tile) that the place maps do not have. Everything here is downscaled to 16 px: the plane's HD sheets 32 -> 16 (x1/2),
the MV sheets 48 -> 16 (x1/3), area-averaged in premultiplied colour with the alpha made crisp - the world-art
pipeline's rule.

## What it writes

| tileset | from | what |
|---|---|---|
| `player_land` | `world/tilesets/player_terrain_hd`, `world/structures/player_structures_hd` | the player's ground (Player, Player_1..3 - the base and its patch bands) and the eight structures (crater, tree, tree2, tree3, tree4, rock, mountain, hole). One terrain set per kind, all 47 blob shapes; structures collide. |
| `mv_outside_a2`, `mv_world_a2` | `Outside_A2.png`, `World_A2.png` | every filled autotile block as a blob terrain set (`r<row> c<col>` = its slot on the sheet) |
| `mv_outside_a1` | `Outside_A1.png` | the first frame of each animated autotile (columns 0 and 4 of every row); no animation |
| `mv_outside_a4` | `Outside_A4.png` | roofs as blob sets, walls as 16-piece edge sets (`roof <band> c<col>`, `wall <band> c<col>`) |
| `mv_outside_a5`, `mv_outside_b`, `mv_world_b`, `mv_world_c` | the plain sheets | plain tiles in sheet order |
| `mv_walls` | `Walls.png` (round 342) | castles, houses, towers and city walls, plain tiles in sheet order |
| `mv_statues` | `Statues.png` (round 342b) - the MV Dungeon composite rip; its B sheet is cut at `STATUES_B` (x 388, y 726) | statues, pillars, ore piles, crystals, coffins, plain tiles; the sheet keeps its transparency |
| `road` | `Road.png` block row 1 col 2 (round 342, the cobbles the user boxed) | one blob terrain set `road`, the sand keyed out (`key_out_sand`: pixels yellower than R-B 40 go clear), so it lies over any ground; no collision |

Every MV tileset comes twice: `name.tsx` with no collision and `name-collide.tsx` where every tile has a full box -
the same pairing as the stock `main.tsx` / `main-nocollide.tsx`. A map can hold both; use the `-collide` one for
the tiles that are obstacles (walls, trees, fences) and the plain one for ground and decoration. `player_land.tsx`
already collides on its structures and not on its ground.

Empty slots on a sheet (the user's Outside A2 rip fills 8 of 32 blocks, Outside A4 15 of 40) are skipped.

## Painting in Tiled

1. Open the map (`maps/map/towns/player_capital.tmx`). Map > Add External Tileset... and pick the `.tsx` files from
   `maps/tileset/` (they sit two folders up from `towns/`, Tiled writes the relative path).
2. Terrain Brush (the T key, or the Terrain Sets panel): pick a tileset, then a set - `tree`, `ground Player`,
   `r0 c1` - and paint; Tiled chooses the piece from the 47 (walls: 16) for every neighbourhood, the way the
   overworld does. Each set's second colour, `none`, erases back to the set's empty tile.
3. Keep the map's object layers as they are (the shops, the arena, the NPCs, the spawn) - redo the tile layers
   under them. A tile layer named for collision does nothing; collision comes only from a tile's own collision box
   in its tileset (`<objectgroup>` per tile), which is why the `-collide` variants exist. Tiled's Tile Collision
   Editor can still give any tile a custom shape.
4. The engine reads tile collision shapes with `MapStage`'s layer walk, so a tile from `name-collide.tsx` blocks the
   whole tile. For half-height fences or table tops, edit the shape in Tiled on that tileset.
5. Test: package (`python standalone-packaging/build_standalone.py`) and enter the Capitol, or the agent
   (`dev-tools/agent`, `cmd goto poi="Player Capital"`, `shot`).

## The templates (round 342)

`make_templates.py` rebuilds `player_town.tmx` and `player_capital.tmx` from `maps/map/towns/` onto the player's
ground and writes them to the user's `Player_Cap` folder (`--out`): the Background layer becomes the player land's
base tile, two patch bands of one value-noise field (Player_1 at <= 0.25, the light Player_3 art at >= 0.75 - not
the overworld's Player_2 gravel, which read as bits of road next to the cobbles, round 342b) go on the Ground layer
where nothing sits, and in the Capitol the White Cap sand islands leave
Ground and every visible road cell is repainted with the `road` set - courtyard roads stay on Walls (above the
courtyard's brick in Ground2), the rest on Ground. Road and sand cells the old layout had buried under the
courtyard's brick are dropped, not repainted. `--renders <folder>` writes before_/after_ PNGs at 2x. The templates
reference their tilesets by absolute path (Tiled relativises them on save) and carry every tileset in this folder;
when one comes back into `maps/map/towns/`, re-point the sources at `../../tileset/` and `../../../../common/...`
and run Map > Remove Unused Tilesets. `tmx.py` (read/write/render a .tmx) and `paint.py` (terrain-set painting,
value noise) are the helpers.

## Redoing the run

The generator is deterministic: rerunning overwrites the same files. If a sheet changes (a fuller Outside A2), rerun
and Tiled picks the new image up; the terrain sets keep their names and slots, so painted maps stay valid as long
as the slot's tile ids do not move - a newly filled slot is appended after the existing sets' tiles, not inserted.
