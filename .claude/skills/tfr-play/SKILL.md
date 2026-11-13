---
name: tfr-play
description: Play The Forsaken Realms (TFR) as the player through the in-game agent bridge - start the ISOLATED agent game (its own folder, APPDATA profile and LOCALAPPDATA cache under C:\TFR\agent, never the user's game or saves), then loop read state -> decide -> one command -> settle; Forge's AI plays the duels, or Claude plays them decision by decision in manual mode (section 4b). Use when the user asks Claude to play TFR, run an agent session, soak-test, or check a feature in a running game.
---

# Playing The Forsaken Realms through the agent bridge

The bridge (MOD_SCOPE #117, `forge.adventure.agent`, round 161; isolated setup round 175) is inside the normal game
jar and OFF unless the game starts with `TFR_AGENT_PORT`. Claude drives the overworld, towns, shops, dialogs, items
and decks over loopback HTTP; **Forge's AI plays every duel on the player's seat** unless manual mode is on (section 4b) (the fight stays the player's:
equipment, ante, rewards, statistics). Design: `docs/design/2026-09-09-agent-play.md`. Client:
`dev-tools/agent/tfr_agent.py` (its docstring lists every verb and command). Repo root:
`C:\TFR\repo` - run the commands below from there. `C:\TFR` is a junction to `C:\CLAUDE\PROJECTS\TFR1` (since
2026-10-26); the scripts and the filters below use the `C:\TFR` path.

## 1. Isolation - the rule that makes this safe

- The agent game runs from **`C:\TFR\agent\The Forsaken Realms`** (a mirror of the live folder) with
  **`APPDATA=C:\TFR\agent\profile`** and **`LOCALAPPDATA=C:\TFR\agent\localappdata`**, so Forge keeps its saves, preferences and `forge.log` in
  `C:\TFR\agent\profile\ForsakenRealms`. The user's `%APPDATA%\ForsakenRealms` is never opened, and it can
  run while the user plays their own game.
- **Never** start the bridge from the live folder with the user's profile, and never touch the user's slots.
- Check isolation after a launch: the agent's log is `C:\TFR\agent\profile\ForsakenRealms\forge.log`; the
  user's `%APPDATA%\ForsakenRealms\forge.log` must NOT rotate (a second game on the same profile renames it to
  `forge.<timestamp>.log` at launch). The Load screen lists only the agent's own saves.
- **The cache is the agent's own too** (round 860): the launcher sets `LOCALAPPDATA=<root>\localappdata`, so fonts,
  card art (`localappdata\Forge\Cache\pics`), skins and db never touch the user's `%LOCALAPPDATA%`. Before it the
  agent shared them, and the user's game hung in card loading twice on 2026-10-29 while an agent game ran. Card art
  the agent has not seen yet is fetched online on first show (the copied preference `UI_ENABLE_ONLINE_IMAGE_FETCHER`).
  Never seed the agent cache from, or link it to, the user's `%LOCALAPPDATA%`; `agent_sync.cmd` leaves it alone.
- **An agent copy** (`agent-NNN`, a copy of `C:\TFR\agent` for a parallel round): launch it with
  `TFR_AGENT_ROOT` set, so it gets its own game folder, profile AND cache. From the PowerShell tool:
  `$env:TFR_AGENT_ROOT = 'C:\CLAUDE\PROJECTS\TFR1\agent-NNN'; Start-Process -FilePath cmd.exe -ArgumentList '/c "C:\TFR\repo\dev-tools\agent\agent_launch.cmd" cheats' -WindowStyle Hidden`
  (the child inherits the variable; or copy `agent_launch.cmd` into the copy's root - next to a `The Forsaken Realms`
  folder it uses its own folder). Never hand-edit a copy of the launcher (a pre-860 hand-edited copy shares the
  user's cache). `python dev-tools/agent/agent_setup.py --root <copy>` seeds a copy's profile. Copying
  `C:\TFR\agent` with its `localappdata` seeds the copy's cache (that is the agent's, not the user's). Stop a copy
  by PID, after checking its command line names `agent-NNN` -
  the stop line below and `agent_stop.ps1` match only the main agent folder.
- **Packaging**: the standing rule is "never repackage while javaw.exe runs" - stop the agent game first. After a
  package, refresh the agent folder: `dev-tools\agent\agent_sync.cmd` (robocopy /MIR of the game folder only, a few
  seconds; the profile is untouched). It refuses (exit 1, nothing copied) while the agent game runs: stop it
  first (round 739).

## 2. Start and stop

```
python dev-tools/agent/agent_setup.py        # once: seeds the profile (1280x720 window, sound off); keeps existing files
Start-Process -FilePath cmd.exe -ArgumentList '/c "C:\TFR\repo\dev-tools\agent\agent_launch.cmd"' -WindowStyle Hidden
python dev-tools/agent/tfr_agent.py boot     # waits for the bridge; prints the first usable scene
```

- Launch with **`Start-Process`** from the PowerShell tool (as above). `agent_launch.cmd` starts javaw detached with
  its own `start`, so the game survives the tool call returning (checked in round 301). Do NOT use the Task
  Scheduler (`schtasks /create`): auto mode blocks it as persistence. Do not redirect the game's stdout. Confirm it
  runs: `Get-CimInstance Win32_Process -Filter "Name='javaw.exe'"` lists a javaw whose command line contains
  `C:\TFR\agent\The Forsaken Realms\`.
- Arguments to `agent_launch.cmd` go after the script path, inside the single quotes (`'/c "C:\...\agent_launch.cmd"
  cheats'`): `cheats` enables console commands (`cmd console text="give gold 100"`) and the fog-free state
  (`state --cheat`) - for testing; a real play session is fair (fog of war). A second argument is a folder of freshly
  compiled classes put AHEAD of the jar - the dev loop, no Maven (`agent_launch.cmd cheats C:\...\classes`; compile
  with javac against the agent jar, `C:/` style paths inside `-cp`).
- Stop, from the PowerShell tool (it matches only the agent folder's jar, under either of its names - `C:\TFR` is a
  junction to `C:\CLAUDE\PROJECTS\TFR1` - so the player's game from `...\live` and any other project's games are never touched;
  `agent_stop.ps1` and `agent_sync.cmd` list the same two folders, change all three together):
  `$d = 'C:\TFR\agent\The Forsaken Realms\', 'C:\CLAUDE\PROJECTS\TFR1\agent\The Forsaken Realms\'; Get-CimInstance Win32_Process -Filter "Name='javaw.exe'" | Where-Object { $cl = "$($_.CommandLine)" -replace '/', '\'; @($d | Where-Object { $cl -like "*$_*" }).Count -gt 0 } | ForEach-Object { Stop-Process -Id $_.ProcessId -Confirm:$false }`
  `dev-tools\agent\agent_stop.ps1` does the same, but this machine's execution policy refuses to run scripts
  ("running scripts is disabled on this system").
- The agent's `forge.log` **cannot be read at all** while the game runs - Forge holds a byte-range lock over
  the whole file, so a `FileShare.ReadWrite` handle opens and then every read throws "another process has
  locked a portion of the file", `Get-Content -Tail` included. There is no readable prefix to scrape (round
  276 wrote a helper to try, and deleted it). Stop the game to read the log, or read `/state`'s `agent.log`
  during the session - it carries the `[TFR-Agent]` walk lines, which is what diagnoses a failed walk.

## 3. The loop

1. `python dev-tools/agent/tfr_agent.py state --brief` - scene, player (day, gold, shards, life, location, deck),
   discovered POIs with distance and bearing, visible enemies, the open dialog, clickable UI, quests,
   notifications since the last read.
2. Decide ONE action. Think like the player: the quest log, the town's needs, the deck, the risk of the fight.
3. `python dev-tools/agent/tfr_agent.py cmd <name> key=value ...` - commands block until done (a walk arrives
   or stops, a wait passes days).
4. `python dev-tools/agent/tfr_agent.py settle` - returns to an idle map scene: sits out a duel (the AI plays, sped to 50x - the user's rule, round 688),
   taps the win/lose view's "Back to Adventure", the ante prompt's "OK" (keeps a won card; on a "Card Lost" prompt
   it accepts the loss - never Buy Back or a Bronze Coin, since TFR round 535), Done on a reward screen, and walks
   single-option dialogs. It stops at a real choice, and at once at a Forge prompt whose buttons it does not know
   (since round 592; it used to wait ~2 minutes first), and prints it - answer with `cmd click id=...` (the `forgeUi`
   ids) or `dialogs choose=TEXT`, then `settle` again. `settle --ask-ante` also stops at the Card Lost choice, for a
   session where the player decides.
   Duels in the agent game start at 50x on their own (round 759: the bridge sets the duel's start speed, `[TFR-Agent]
   duel speed set to 50x (agent default)` in forge.log), so a duel no client settled still runs fast (auto-battle, the default; with `autobattle on=false` the seat is yours and has no speed button); settle still applies it.
5. Repeat. Every few steps `shot` a screenshot (`tfr_agent.py shot C:/path/x.png`) and look at it - the state is
   objects, the screenshot is what the player sees.

## 4. Commands (see the client docstring for the full list)

- World: `goto poi="Name"` | `goto tile=x,y` | `explore dir=NE tiles=10` | `wait days=2` | `fasttime on=true` |
  `stop`
- Map (town/dungeon): `goto actor=<id>` (shops, NPCs, rewards, enemies, the exit or portal) | `leave`
- Any scene: `click id=<id>` | `click text="Done"` | `advance` (finish typing text) | `back` | `key key=ESCAPE`
- Model: `equip item=...` | `use item=...` | `sell item=...` | `deck op=list|select index=1|add card=... count=2`
  | `buy index=0` | `save slot=1 name="agent"` | `load slot=1` | `newgame`
  | `startingsets sets=DTK,any` (round 873: sets the New Game screen's Starting sets drop-downs in order; `any` = Random)
- Duels: auto-battle by default (Forge's AI plays the seat). `duelmode mode=manual` makes the next duels YOURS,
  decision by decision: section 4b.

## 4b. Manual duels: Claude plays the player's seat (round 1023)

For training and diagnosis only, and only in the agent game: finding what a human exploits in Forge's AI (tricks into
open mana, bait attacks, predictable blocks). It is never for live play or the user's game. The study format and the
first findings are in `C:\CLAUDE\PROJECTS\TFR1\notes\r1023\study.md`.

- **Turn it on:** `python dev-tools/agent/tfr_agent.py duelmode mode=manual`. From then on, every duel the bridge would
  auto-battle waits for you at each decision. Guard fights and spectated duels stay the AI's. `duelmode mode=auto` goes
  back. Set mid-duel, the AI finishes that duel. `duelmode autopilot=true` hands only the current duel to the AI.
- **Difficulty:** not Easy. The TFR AI rules skip Easy; use a Normal or harder save.
- **Speed:** the AI's turns still run at the duel's 50x (the user's rule). Only your decisions wait. A prompt left
  unanswered for 30 minutes (`fallback=`) goes to the AI, so a forgotten duel cannot hang the game.
- **The loop:** start a fight as usual (walk into an enemy; with cheats, `console text='spawn enemy "Badger"'`). Then:
  1. `tfr_agent.py duel` shows the waiting prompt and the board: life, hands (the opponent's as a count), lands,
     creatures with P/T, tapped, sick, damage, counters and keywords, the stack, combat, what happened since your last
     decision, and each new card's oracle text once.
  2. Answer with ONE verb. It blocks until the game asks again (the AI's turn plays out meanwhile) and prints the new
     board and prompt:
     - `keep` | `mulligan`; the bottom cards: `choose ids=12`
     - `play n=3`: a land, a spell or an ability from the numbered `plays`; `targets=45` | `"45,opp"` (two in one
       step) | `"45;opp"` (one group per targeting step) | `x=3` | `modes=0,2` | `optional=0` (kicker...)
     - `pass` (`until=turn`: no empty-stack stops until the next turn)
     - `attack ids=12,14` (`at=opp` or a planeswalker's id) | `attack pairs="12:opp,14:88"` | `noattack`
     - `block pairs="20:12,21:12"` (blocker:attacker; two on one attacker is fine) | `noblock`
     - `choose ids=45,46` (lists, in order) | `choose yes=true` (confirms) | `choose n=2` (numbers) | `concede`
  3. When it prints `gameOver`, `settle` takes the win/lose screen and the rewards, as for any duel.
- **Read every prompt before the next verb.** After a land drop with nothing castable, the next prompt is the attack,
  not main phase 1. Each verb names the prompt it answers and is refused at another one (nothing happens), but two
  priority prompts look alike. `pass expect=` (empty) overrides the check.
- **The default stops** ask only when you have a legal play: your main phases and after blocks on your turn; the
  opponent's attack and block steps (when something attacks) and end step; any stack holding the opponent's spell or
  ability. Your own spell resolves without a question. `duelmode stops=all` asks at every priority.
- **Still the AI's:** mana payment (it taps the lands), the targets of your triggered abilities, combat damage
  assignment, and rare choices (colors, card names, votes). The seat is an AI seat under the hood (`isAI()` is true).
- **Cost:** about 25-50 decisions and 3-5 minutes a duel against Apprentice/Adept enemies (round 1023).
- **Logs:** `[TFR-ManualSeat]` lines in forge.log and `/state`'s `agent.log`: the mode, every answer, every refusal and
  what it did. `[TFR-Duel]` ends the duel as usual.
- **Write down** every AI decision that looks exploitable, with the turn and the board, as a candidate puzzle. Never
  build a rule from one duel.

## 5. A new game

On a fresh profile the scene is StartScene: `cmd click text="New Game"`, then `cmd newgame` (it uses the New Game
screen's current settings - name, race, difficulty, mode; a fresh profile offers a random name, Normal, Constructed).
An InfoTextScene follows - it is the plane's WELCOME PAGE (once per save; to check it, screenshot it before pressing Back) - (`cmd click text="Back"`), then `dialogs choose="Skip the introduction"` (or play the
intro) and `dialogs`. Walk out through the portal: `state --brief` lists it as a map actor -> `cmd goto
actor=<portal id>`. The profile is the agent's own - save to any slot (`cmd save slot=1 name="agent start"`) before
anything risky, and `cmd load slot=1` from the Load screen to get back.

## 6. Traps (all learned the hard way)

- **Leaving a town.** The game switches the player's world collision OFF for ~2 s after an exit (the arrival
  flicker, so a person can step away); a walk issued after that, still standing on the town, used to walk straight
  back in. Since round 175 the walker exempts the town it stands on (`[TFR-Agent] standing on ...`) and never paths
  through the ring right next to a point of interest. If a walk still re-enters the town you just left, report it
  with the `[TFR-Agent] walk start:` line from `agent.log` (player rectangle, collision height, nearby POI
  rectangles).
- **Touching ANY point of interest enters it** - footprints are obstacles for the planner; the destination is the
  only one it walks onto.
- **Long walks through unexplored land can end "stuck near (x,y)"** after four replans. Walk in `explore` legs
  toward the target's bearing, then `goto` again.
- A MapDialog hides its buttons until its text finishes typing: `advance`, or `dialogs` does it for you.
- An interception on the way stops a `goto`: `settle`, then issue the same `goto` again.
- The win/lose view and option panes are Forge widgets, listed as `forgeUi` in the state - `settle` taps them.
- **Loot is collected only through the reward screen's Done** (listed by NAME, `done`; its text is the `[+OK]`
  glyph). `cmd back` on a RewardScene leaves WITHOUT collecting (UIScene.back -> switchToLast). Before round 179
  `settle` matched the button's text only, missed it and pressed `back` - every loot screen it passed was thrown
  away (the round-176 watched session included). It clicks Done by name now and never backs out of a loot screen.
- The ante Re-roll prompt is skipped for the agent's seat (it would be a modal nobody can click).
- Console arguments split on spaces, but quotes group them: `console text='spawn enemy "Wild Rat"'` works,
  `console text="spawn enemy Wild Rat"` looks for "Wild". A spawn lands 3-10 tiles away, usually under the fog -
  `console text="torch pulse"` reveals 9 tiles for a screenshot; `state --cheat` lists them regardless.
- The stock AI pays any "PayShards" cost with 0 shards and a spectated seat's shards are never written back - in an
  auto-battle the AI can use the player's shard abilities for free. Report it rather than exploit it.
- `wait days` steps off the POI first; a roaming enemy often finds a waiting player - that is a duel.

- **Testing the Player Capitol map** (round 343): a fresh world's Orazca uses `orazca.tmx`; `player_capital.tmx` only
  loads once the Capitol is raised. Cheats on, stand in Orazca: `console text="give gold 2000"`, `give wood 200`,
  `give stone 200`, `give shards 100`, then `console text="capitol raise"`, `cmd leave`, and
  `console text='teleport to poi "Player Capitol"'` (the walker's `goto poi` stops at the doorstep of a town you
  stand beside; the teleport enters it). `player_town.tmx` is what OTHER restored towns render from.
- **`cmd goto tile=x,y` inside a map is y-up**: Tiled row = map height - 1 - y. `state` gives `player.pos` in map
  pixels (16 a cell), also y-up.
- **Walking inside maps (round 448).** The walker plans on the map's own collision with the player's box and keeps off
  every stairs/portal/exit except the one you `goto` - plain `goto actor=<stairs id>` works through multi-level places
  (Cultists' Outpost's four floors needed hand waypoints before). Taking stairs ends the walk with "the level changed";
  read the new floor's actors before the next goto. A snag logs `[TFR-Agent] snagged at (x,y): box ..., walls near:
  ...` in `/state`'s agent log - the walls the player is up against. An enemy beside an arrival point still starts a
  duel before any `remove enemy` lands.
- **`leave` (console or bridge) refuses during a duel** since round 448 - leaving a map mid-duel closed the game.
- **Tappable text lines are clickable** (round 452): a label with a click handler (the Research Lab's set lines) is
  listed in `ui` after the buttons, by its text - take a fresh `state`, then `cmd click id=uN`. The Lab itself is a map
  trigger, not an actor: in the Capitol (`teleport to poi "Player Capitol"`) walk to `goto tile=10,33`.
- **Run the client with `PYTHONIOENCODING=utf-8`** (`export PYTHONIOENCODING=utf-8` in the Bash tool): a reward screen's
  checkbox glyph (U+2610) otherwise crashes `settle`'s print on the Windows console.
- **Run `tfr_agent.py` with `python -X utf8`, not `python -I`**: `-I` drops `PYTHONIOENCODING`, and `settle` crashes on the
  reward screen's checkbox glyph again.
- **The agent profile's window must stay 1280x720.** At 3840x2160 it covers the screen, gets minimized, and a minimized
  game stalls: the bridge times out and shots come back 1x1. `agent_setup.py` seeds 1280x720; put it back if it changed.
- **The window can get minimized even at 1280x720**, mid-duel or during world generation (rounds 764 and 769): the same
  bridge timeout and 1x1 shots. Restore it with `ShowWindow(SW_RESTORE)` on the agent's javaw window, from PowerShell:
  `Add-Type -Name W -Namespace U -MemberDefinition '[DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr h, int c);'`
  then `Get-Process javaw | ? { $_.Path -like '*\agent\*' } | % { [U.W]::ShowWindow($_.MainWindowHandle, 9) }`.
- **A fresh New Game is the clean test for one-time map things** (a hidden pickup, a first-visit dialog): a long-running
  save has usually used them up. `console text="set charflag NAME 1"` / `... 0` (0 removes it) fakes a run state such
  as `newGamePlus`.
- **Killing the agent game loses the end of `forge.log`** (it is buffered). To read a startup line, quit through the
  menu: `cmd click text="Exit"`, then a fresh `state` (dialog ids need one), then `cmd click id=d1`; the log is
  complete a second or two later. Kill only when the log does not matter.
- **The bridge cannot scroll a list** (Settings, long shops): `click id=` on a widget below the fold answers
  `clicked null` and does nothing, and arrow keys do not scroll. For a setting, edit the AGENT profile's
  `C:\TFR\agent\profile\ForsakenRealms\adventure\settings.json` (back it up first, restore after).
- **Console commands with quoted arguments go through the Bash tool**: PowerShell 5.1 strips the embedded quotes
  (`teleport to poi "White Castle"` arrives as `White`).

## 7. Reporting

Keep a short running log (what, why, result). At the end: where the character is, what changed (gold, cards,
quests, towns), every surprise or bug with the `[TFR-*]` log lines around it, and a few screenshots. Leave the
game stopped unless the user wants to watch.
