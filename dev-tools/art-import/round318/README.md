# Round 318 import - 43 creatures from RPG Maker MV/MZ sheets (+ 4 Kamigawa dragon re-skins)

Prepared in the round-316 scratchpad, run in round 318. The source is the user's `Desktop\New Enemy Art` folder
(RPG Maker MV/MZ character sheets: 3 frames x 4 facings, top-down 3/4 view, no attack/hit/death rows).

- `roster316.py` - the picks: name, sheet, variant, colors, rank, flying, stationary, deck theme; `RESKINS` (Jugan,
  Yosei, Ryusei, Keiga -> the coiled dragon rows).
- `build_atlases.py` (with `tools/rpgm.py`) - fixed-grid slicer into 4-direction atlases (`IdleDown`, `WalkLeft`, ...;
  fliers cycle their Idle; front-only sheets get un-suffixed `Idle`/`Walk`), one trimmed cell size per atlas, feet or
  a flier's shadow on the bottom row. `--key-shadow` (bats, horrors) turns the sheets' opaque gray shadow translucent.
  `tools/rpgm_to_atlas.py` is the survey's first prototype.
- `deckgen316.py` / `deck_audit316.py` - the round-179 generator with the new themes (kirin, behemoth, werewolf, ...).
- `import316.py <repo root> [--dry]` - REQUIRED root; reads every file live and appends (sprites/enemy/tfr,
  decks/standard/tfr, enemies.json, six biome rosters, six capital arenas, enemies.csv, CREDITS.md, the re-skins'
  sprite paths). Idempotent; refuses a half-done state. Then `dev-tools/enemy_scale.py --write` and the validator.
- Do NOT run `dev-tools/gen_caves.py` for these: it rewrites all 78 caves and would wipe rounds 257/258/286's cave
  guards and patrol repairs.
