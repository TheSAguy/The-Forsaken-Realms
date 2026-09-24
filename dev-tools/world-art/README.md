# world-art - the overworld's structures, doodads and grounds (rounds 303-309)

The toolchain that turned the user's RPG Maker sheets into the plane's 32 px world art. It lived in a session
scratchpad from round 303 to 308 and moved here in round 309, byte-for-byte: `export.py` with round 303's `spec.py`
reproduces the plane's `*_structures_hd.*`, `doodads_hd.*`, `map_sprites.json` and biome `spriteNames` exactly.

- **Inputs** stay outside the repo: the user's sheets in `C:\Users\User\Pictures\Screenshots\Terrain\` (`paths.ART`),
  credited in `standalone-packaging/CREDITS.md`.
- **Outputs** of the preview tools go to `C:\TFR\art-staging\world-art\` (`paths.OUT`, or `WORLD_ART_OUT`).
- **The plane** is written only by `export.py <repo root>` (`--dry` to list what it would write).

| File | What it is |
|---|---|
| `spec.py` | THE DESIGN - every land's structures (autotile areas) and doodads (kinds, pictures, tints, noise bands, densities) |
| `sources.py` | sprite refs (`cy:`, `rdx:`, `rd:` from round 303; `<sheet>:c,r` / `c,r#k` / `@x0,y0,x1,y1` from the user's other sheets, `SHEETS`), tint, fit, autotile helpers |
| `build.py` | builds a land's structure sheets and doodad pictures from the spec; `python build.py preview` writes before/after previews |
| `export.py` | writes the plane: structure sheets, `doodads_hd.png/.atlas`, `map_sprites.json` entries, biome `spriteNames`, the ocean's plane copy |
| `cellcat.py <sheet> [c0,r0,c1,r1] [--whole]` | a catalog of a sheet's objects with the ref that picks each |
| `segment.py` | round 303's cut of a transparent sheet into numbered boxes (`boxes/`) |
| `a1_convert.py`, `a2_convert.py` | MV A1/A2 autotiles -> XP at 32 px (`autotiles/`) |
| `whirlpool.py` | the ocean's whirlpool picture |
| `render.py`, `atlas.py`, `paths.py` | BiomeTexture's autotile drawing ported, an .atlas reader, the paths |
| `ground_options/` | every ground option the user was shown and `set_ground.py` to switch one (round 307) |

A save picks up new doodads through `World.DOODAD_SET` (a one-time re-scatter) and new ground art through
`World.migrateGround()` (round 307, automatic).
