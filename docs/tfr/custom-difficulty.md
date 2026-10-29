# Custom difficulty - how it works (rounds 822-833, 839)

A save can carry its own difficulty on top of its preset. This page is the map for a session that has to change it,
review it, or read a player's log. The round-by-round detail is in `MOD_CHANGELOG.md` (rounds 822-833 and 839); the
design study is `C:\TFR\notes\custom-difficulty-feasibility.md`, the plan `C:\TFR\notes\custom-difficulty-round-plan.md`,
and each data batch's reader audit `C:\TFR\notes\r829..r833-*.md`.

## The pieces

- **The knob list**: `forge-gui/res/adventure/The Forsaken Realms/config tables/custom_difficulty.json` - 183 knobs
  (round 833). Each has an id (the save's key - never rename one a release shipped), a label, a tab (`category`) and
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
  ID VALUE`, `reset ID|all`, `next ID VALUE` / `next clear`, `panel [search TEXT | tab NAME | code [paste TEXT]]`.

## Reading a log

- `[TFR-Custom] N knob(s) from config tables/custom_difficulty.json` at start - a `knob X skipped - why` line means a
  bad target or range in the json.
- `[TFR-Custom] load/new game/New Game+: Custom (Hard), N override(s): id a -> b, ... | rules: ...` - what the save
  plays with; `dropped` / `clamped` lines say a save held a value the json no longer allows (it is clamped, not lost).
- `[TFR-Custom] changed ID a -> b on day N (source)` - every mid-game change (Settings, the panel, the console).
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
