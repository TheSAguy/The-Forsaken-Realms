# Ground options (rounds 305-307)

Every ground the user was shown for the lands, kept so any of them can be switched in later (the user, round 307:
"don't get rid of any of the other options. I might want to switch some out"). The source sheets are the user's own,
in `C:\Users\User\Pictures\Screenshots\Terrain\` (RPG Maker MV/VX A2 autotile blocks, 96x144 at 48 px). Each was
converted to the XP layout the game draws (96x128 at 32 px: area-average downscale, VX -> XP minitiles), and stamped
with its minimap swatch in the top-left 4x4. Credits: `standalone-packaging/CREDITS.md`.

- `<land>.png` - that land's options, one row each (A at the top): base | patch 1 | patch 2. The wasteland's E
  (round 307, the user: A's purple patch read as Black's ground - "change it from Purple to kinda brown") is A with
  B's dead-earth patch.
- `player_source.png` - the player's 32 px ground before toning (round 300's art; in use at 35% toward the old green).
- `previews/options_<land>.png` - the sheets the user picked from (each option with the land's structures and
  doodads); `previews/patch_sizes.jpg` - the patch sizes (round 307 picked B).
- `set_ground.py` - the switch. `python set_ground.py <repo root> <land> <letter>`, `... <land> old` (the stock 16 px
  ground), `... player <tone 0..1>`, `... patches <A|B|C|old>`. Then build and package as usual: a save follows on its
  next load (its patches are laid out again if the bands changed, its map image re-baked if the art changed -
  `World.migrateGround()`).

| Land | Option | Name | Base | Patch 1 | Patch 2 |
|---|---|---|---|---|---|
| white | A | savanna  **(in use)** | deser_a2 4,0 | World_A2 3,1 | deser_a2 4,1 |
| white | B | golden steppe | bhUpd0H 11,2 | bhUpd0H 15,0 | bhUpd0H 3,2 |
| white | C | desert | World_A2 3,1 | bhUpd0H 5,0 | ZCrOljM 1,3 |
| white | D | pale steppe | bhUpd0H 15,0 | bhUpd0H 11,2 | bhUpd0H 3,2 |
| blue | A | beach  **(in use)** | World_A2 3,1 | ZCrOljM 5,3 | ZCrOljM 0,3 |
| blue | B | blue meadow | bhUpd0H 8,0 | bhUpd0H 7,0 | bhUpd0H 14,0 |
| blue | C | tidal flats | bhUpd0H 14,0 | bhUpd0H 7,0 | World_A2 3,1 |
| blue | D | sea-green | bhUpd0H 10,0 | bhUpd0H 9,0 | ZCrOljM 5,3 |
| black | A | bog | bhUpd0H 11,0 | bhUpd0H 9,0 | bhUpd0H 12,2 |
| black | B | dark teal moor | bhUpd0H 9,0 | bhUpd0H 10,0 | bhUpd0H 12,2 |
| black | C | rotting purple  **(in use)** | bhUpd0H 12,2 | bhUpd0H 7,2 | bhUpd0H 11,0 |
| black | D | ashen | bhUpd0H 14,2 | bhUpd0H 12,2 | ZCrOljM 5,0 |
| red | A | red earth | bhUpd0H 9,2 | bhUpd0H 8,2 | bhUpd0H 2,0 |
| red | B | canyon  **(in use)** | bhUpd0H 3,0 | bhUpd0H 2,0 | World_A2 3,1 |
| red | C | scorched | bhUpd0H 4,0 | World_A2 3,2 | bhUpd0H 2,0 |
| red | D | rust and ash | bhUpd0H 6,0 | bhUpd0H 4,0 | bhUpd0H 14,2 |
| green | A | meadow | bhUpd0H 0,2 | bhUpd0H 1,2 | bhUpd0H 0,0 |
| green | B | deep forest  **(in use)** | bhUpd0H 0,0 | bhUpd0H 10,0 | ZCrOljM 3,0 |
| green | C | spring | deser_a2 4,2 | bhUpd0H 1,2 | ZCrOljM 3,3 |
| green | D | emerald | bhUpd0H 5,2 | bhUpd0H 0,0 | bhUpd0H 4,2 |
| colorless | A | ash grey | bhUpd0H 14,2 | bhUpd0H 12,2 | bhUpd0H 16,2 |
| colorless | B | dead earth | bhUpd0H 4,0 (tinted: saturation 0.3, value 0.9) | bhUpd0H 11,0 (tinted: saturation 0.3, value 0.9) | bhUpd0H 14,2 |
| colorless | C | cold slate | bhUpd0H 14,0 | bhUpd0H 12,2 | bhUpd0H 14,2 |
| colorless | D | charcoal | bhUpd0H 12,2 (tinted: saturation 0.5, value 0.75) | bhUpd0H 14,2 | ZCrOljM 5,0 (tinted: saturation 0.25, value 0.8) |
| colorless | E | ash grey, dead-earth patch  **(in use)** | bhUpd0H 14,2 | bhUpd0H 11,0 (tinted: saturation 0.3, value 0.9) | bhUpd0H 16,2 |

Patch sizes (`set_ground.py <repo root> patches <pick>`, every land at once; the player's overlay patch follows the
resolution): **old** - the colors at resolution 10, the wasteland and the player at 5, bands 0.2 / 0.8 (tiny dots);
**A** - 4, 0.2 / 0.8 (bigger, same amount); **B** - 3, 0.25 / 0.75 (bigger and a bit more, in use); **C** - 2,
0.25 / 0.75 (much bigger).
