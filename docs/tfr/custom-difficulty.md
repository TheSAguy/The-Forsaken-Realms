# Custom difficulty - how it works (rounds 822-833, 839, 939)

A save can carry its own difficulty on top of its preset. This page is the map for a session that has to change it,
review it, or read a player's log. The round-by-round detail is in `MOD_CHANGELOG.md` (rounds 822-833 and 839); the
design study is `C:\TFR\notes\custom-difficulty-feasibility.md`, the plan `C:\TFR\notes\custom-difficulty-round-plan.md`,
and each data batch's reader audit `C:\TFR\notes\r829..r833-*.md`.

## The pieces

- **The knob list**: `forge-gui/res/adventure/The Forsaken Realms/config tables/custom_difficulty.json` - 184 knobs
  (round 833, plus round 872's Starting sets). Each has an id (the save's key - never rename one a release shipped), a label, a tab (`category`) and
  sub-heading (`group`), a tooltip, `scope` (`anytime` or `newGame`), `level` (`basic`/`expert`), `mode` (`set` replaces,
  `scale` multiplies every target's shipped value), `min`/`max`/`step`, an optional `format: percent` and `rebalance`,
  and one or more targets. The header comment lists every field deliberately left out and why.
- **Target sources** (`util/CustomDifficulty.resolve`):
  - `tuning.f` (settings.json / TuningData), `tuning.f{D}` (the Easy..Insane columns), `tuning.f[*]` (every entry);
  - `difficulty.f` (the save's DifficultyData, laid over the config preset);
  - `rule.f` (the player's own switches, per save - never "Custom", logged as `rules:`);
  - `config.f` (config.json flags, a per-save ConfigData copy);
  - `spawn.f`, `armory.f` (round 831; row targets such as `spawn.weekBrackets.rare` reach a field of every row; a scale
    keeps 0 at 0; `"rebalance": "common"` gives each week row its own total back through the Apprentices);
  - `ascendance.f`, `legends.f`, `guards.f`, `war.f` (round 832).
- **Per-save copies**: `CustomDifficulty.applyTuning(map)` builds every copy and puts it in place (null = the plane's
  own): `Config.setEffectiveTuningData` / `setEffectiveConfigData` / `setEffectiveSpawnTierWeightData` /
  `setEffectiveArmoryRarityData`, and `setEffective` on `Ascendance`, `LegendSpawns`, `RoamingGuards`, `WarChampions`.
  No call site changed: every reader already went through one getter. The base getters (`getBase*`, `base()`) are the
  plane's own values. TuningData/ConfigData/the four plain tables are field copies (arrays copied); the spawn and Armory
  tables are deep copies (a libGDX Json round trip - their rows are objects).
- **When it is applied**: first in `AdventurePlayer.load` (the save's `customDifficulty` key, `id=value;...`),
  before world generation at a new game (`beginNewGame` -> `WorldSave`), at New Game+ (round 839's edit through
  `replaceForNewGamePlus`), and on every mid-game change (`change` / `setRule`).
- **Scopes**: `newGame` knobs are read only at world creation, stamped into the save, or would break a run if changed
  mid-way (Level cost: the save stores raw Power; raid kills/days/reach: a running raid re-reads them; pillage, the
  Wrath and the side-quest timer: switching them mid-run froze or failed what was under way - round 833). The panel
  locks them mid-game; `custom set` refuses them (`custom next` queues them for the next new game).
- **UI**: `scene/CustomDifficultyScene` (the Advanced panel - new game, mid-game from Settings > Difficulty, New Game+;
  share codes), `scene/NewGameScene` (Advanced...), `scene/SettingsScene` (the Difficulty row and the rule rows),
  `scene/SaveLoadScene` (New Game+'s Custom settings row).
- **Console** (`custom ...`): `list`, `knobs` (every knob, its range, scope, the preset's value and the live one), `set
  ID VALUE`, `reset ID|all`, `next ID VALUE` / `next clear`, `panel [search TEXT | tab NAME | code [paste TEXT]]`;
  round 939: `panel open` (the panel on the running save - the agent bridge cannot scroll Settings to its Edit...),
  `panel changelevel LEVEL`, `panel cancel`, `panel levelcheck` (the Change level self-check).

## Changing a save's level (round 939)

Until round 939 a save's preset was fixed for the run ("the preset stays the save's difficulty"). **That rule is gone:**
the mid-game panel's **Change level...** (Easy / Normal / Hard / Insane, any number of times) makes the running save a
save of another level (`CustomDifficulty.changeLevel`). The design and its audit: `C:\TFR\notes\r939-audit.md` and
`r939-routeB.md`.

- **What moves:** the save's `difficultyData` takes the new preset's name and run fields (`runFields`: spawnRank,
  enemyLifeFactor, sellFactor, shardSellRatio, goldLoss, lifeLoss, rewardMaxFactor). Every rule that reads the level by
  name or index follows with no knob: enemy decks (genetic AI on Hard/Insane), building prices, the rank tilt
  (`difficultyFactors`), item cracking, the purse factor, dungeon spawns, invasion leaders, the {D} / [*] columns.
- **What is held:** Ascendance Power is re-priced to the new level cost (`Ascendance.onLevelChange`, round 545's
  `reprice`), so the level stays and the next load re-prices nothing; a switch INTO Hard/Insane forgives the reloads the
  world already counted (`ReloadJab.onLevelChangeIntoHard`, round 732's no-retroactive rule); the starting sets stay the
  first level's (`startingSetsOf`, read again at every load). The starting fields (life, gold, kit) stay the start's.
- **Overrides:** a change equal to the new level's own value is dropped (`equalTo`); the rest and the Rules carry on.
  The panel sets them aside when a level is picked and puts them back if the pick changes.
- **Never during a duel or its rewards** (`levelChangeBlocked`; the map menu is frozen then anyway).
- **The record:** the save's `levelHistory` key ("Hard@0,Normal@34"; absent = never changed, so older saves are as
  before; New Game+ starts it over). Words: Settings "Normal (started on Hard)" (`displayName`), the save slot "Normal
  (from Hard)" (`slotName`; `SaveLoadScene.presetOf` reads the level back), Settings' sub-line "Changed on day 34."
  (`levelDetail`), the info page "Normal (from Hard)" (`levelNote`), `[TFR-Session] difficulty=Normal startedOn=Hard
  levelChanges=1`.
- **Panel:** Change level... and Cancel (Cancel throws away every unapplied edit, in every mode; Esc stays Done). A
  picked level waits for Done, which switches first and then applies the rows (measured against the new level).

## Reading a log

- `[TFR-Custom] N knob(s) from config tables/custom_difficulty.json` at start - a `knob X skipped - why` line means a
  bad target or range in the json.
- `[TFR-Custom] load/new game/New Game+: Custom (Hard), N override(s): id a -> b, ... | rules: ...` - what the save
  plays with; `dropped` / `clamped` lines say a save held a value the json no longer allows (it is clamped, not lost).
- `[TFR-Custom] changed ID a -> b on day N (source)` - every mid-game change (Settings, the panel, the console).
- `[TFR-Custom] level changed Hard -> Normal on day N (source): Power a -> b (level cost ...), still level L; reload jab
  ...; startingSets kept S; dropped N override(s) ...; history ...` - a Change level (round 939); `panel: Change level
  ... waiting for Done`, `panel cancelled ...` and `level change ... refused - not during a duel or its rewards` around it.
- `[TFR-Session] game ... difficulty=Custom(Hard) custom=N rules=...` and the footer's `custom=`/`rules=`/`customChanges=`.
- **Log-review checklist line**: when a report reads oddly (enemies too strong or weak, a price, Power, a raid), grep
  `[TFR-Custom]` first - a `custom=` above 0 means the save's numbers are not the preset's, and the override list names
  which. The GUIDE's numbers are the presets'.

## Changing it

- A new knob: add it to the json (an existing field and source, or a new source in `resolve`/`tableClass`, a copy, and
  an `applyTuning` line), check its readers (at use, or stamped / generation-only -> `newGame`), and set min/max from
  what breaks (0 that means "default" or "off", negative costs, crossing pairs, empty pools). Ordered sets go through one
  scale knob.
- Defaults never change for a save without overrides: a knob absent from the save is the preset.
