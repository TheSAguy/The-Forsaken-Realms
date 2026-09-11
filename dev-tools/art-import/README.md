# art-import - new enemy art into the plane (round 179)

The toolchain that turned the user's art folder (227 sheets: 124 usable Ragnarok Online sheets and 73 generic 3D /
comic renders) into 197 enemies with sprites, themed decks, stats, rewards and spawn tables. Inputs and the converted
atlases live OUTSIDE the repo in `F:\FORGE\TFR-Art-Staging\` (`ro\`, `gen\`, `decks\`, `qa\`); these scripts are the
record of how, and the start of the next import. Licensing: the user decided on 2026-09-11 to use this art; the
sources are credited in `standalone-packaging/CREDITS.md`.

| Step | Script | What it does |
|---|---|---|
| 1 | `art_triage3.py <art> <out>` | first pose of every animation row at game size; writes `art_rows.json` (row/frame boxes) |
| 2 | `art_bands.py` | band strips of the generic sheets, to label Idle / Walk / Attack / Death / portrait by eye |
| 3 | `art_convert.py <art> <out>` | Ragnarok sheets (The Spriters Resource layout) -> atlases: labels, brackets and boxes dropped, front view only, flipped to face right |
| 3 | `art_convert_generic.py <art> <art_rows.json> <out>` | generic sheets -> atlases; per-sheet fixes in `overrides_generic.json` |
| 4 | `art_preview.py`, `art_catalog.py` | QA and naming contact sheets |
| 5 | `roster179.py` | THE ROSTER: slug, name, rank (A/D/M/X), colors, deck theme, extra quest tags - 197 rows |
| 5 | `roster_check.py`, `roster_rebalance.py` | color / rank / mono-two-three balance against the current roaming cast; the one-shot rebalance |
| 6 | `carddb.py` | Forge's card scripts + editions -> `carddb.json` cache (paper sets only, no Universes Beyond, restricted list honored) |
| 6 | `deckgen179.py [out]` | a themed deck per enemy (Apprentice 40 cards, Adept+ 60); deterministic (CRC32 seed per slug) |
| 6 | `deck_audit.py [dir]` | size, lands, on-theme creatures, color coverage, curve, drawback creatures |
| 7 | `import179.py [--dry]` | into the plane: `sprites/enemy/tfr/`, `decks/standard/tfr/`, 197 `enemies.json` entries, biome rosters, capital arena pools, `config tables/enemies.csv` |
| 8 | `frame_qa.py <atlas dir> [--sheet x.png]` | per-frame check: a second figure in a cell, oversize / near-empty frames |
| 8 | `atlas_fix179.py <atlas dir>` | drops the frames `frame_qa.py` found (checked by eye): brackets, label boxes, portraits, watermark text |
| 9 | `../enemy_scale.py --write` | the sizes (one body size per rank) |
| 9 | `../gen_caves.py --no-register` | the 78 caves re-pick their roamers from the new rosters (walls and floors unchanged) |

Spawn tables chosen in round 179: every enemy in the biome roster of each of its colors (the week-based tier
weighting keeps Masters and Archmages rare early); the undead, horrors and constructs below Archmage also in the
Wasteland's; each AI capital's arena gains up to 8 new Masters and 5 new Archmages of its color, the player capital's
one Adept, Master and Archmage per color; the Chest's illegal arena and the caves' wildcards pick up the new ranks on
their own.
