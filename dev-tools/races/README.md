# Playable races (round 317)

A race is one entry in the plane's `world/heroes.json` (APPEND ONLY - a save stores the race's index), a
`raceEditions` row (its four sets: starting unlocks, the starter decks' card pool, the Standard dropdown) and a
`raceShops` row (two starting shop blueprints) in the plane's `config.json`. Nothing else is keyed on race.

- `make_race_heroes.py <adventure res root> <target plane> [--recolor A|B|"A,angel_m=B"] [--walk-from-idle ...]
  [--preview x.png]` - builds the Goblin/Angel/Merfolk/Vampire hero sheets (64x80, Right block only - the game mirrors
  Left and reuses Right/Left for the other directions), `sprites/heroes/avatar_tfr.*` (the stock portraits verbatim
  plus the new rows) and the plane `world/heroes.json` (the 16 stock races in stock order + the new ones). Round 317
  ran it with `--recolor "A,angel_m=B"`: the heroes are cut from enemy sheets, and the player must never look like an
  enemy (round 156). Never name a hero atlas like the enemy sheet it came from -
  `ContentFilterTables.matchesPlayerHeroArt()` would hide every enemy wearing that sheet.
- Round 322 swapped the angels (angel_1 = male, angel_2 = female) and added `--eyes angel_m=blue,angel_f=blue`
  (gold/blue/green irises on the portraits); the current run is
  `--recolor "A,angel_m=B" --eyes "angel_m=blue,angel_f=blue"`.
- `race_checks.py <plane> [--common DIR --editions DIR --maps DIR]` - heroes.json vs raceEditions/raceShops, hero
  atlases and portraits, booster-capable unrestricted sets, shop names the chooser can offer. Exits 1 on a problem.
