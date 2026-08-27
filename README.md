# The Forsaken Realms

**A free, standalone single-player adventure card game — five colors, one map, no neutral ground.**

The Forsaken Realms turns a trading-card world map into a living campaign. Five rival
colors each rule a castle and carve up the land between them: towns take sides, borders creep
while you sleep, and the color you cross today decides whether its Capitol opens its gates to
you — or charges you a toll to survive the visit. Underneath it all runs the full Forge rules engine,
so every fight is a real card duel with real decks.

**[⬇ Download the latest release](https://github.com/TheSAguy/The-Forsaken-Realms/releases/latest)** · **[💬 Join the Discord](https://discord.gg/TTRPKc9HYJ)** · **[☕ Support on Ko-fi](https://ko-fi.com/thesaguy)**

---

## What makes it different

- **Reputation, with teeth** — five reputation tracks, one per color, from Partner (30% shop
  discount) down to War (barred from their towns outright).
- **Territory that moves** — castles and Capitols expand and contract their borders daily; own
  the ground and you travel it faster.
- **Build a Capitol** — own five towns and one becomes *Orazca*, a true seat of power with
  buildings no ordinary town offers: Bank, Exchange, Archaeologist.
- **Day, night & the march of time** — enemies fight harder on white plains at noon and in black
  swamps at midnight; shops restock weekly, mines pay on payday, quests burn down in real days.
- **Dungeons that live and die** — every dungeon has a lifespan; cleared or failed ones fade from
  the map and fresh ones appear, drawn from a reserve five times larger than what's visible.
- **Wood & Stone economy** — two new resources feed construction, guards' wages, and the Capitol
  upgrade itself.
- **Sets unlock as you go** — editions unlock as you find their cards in the world and research
  what you've found; different lands hold different expansions.

**By the numbers:** 1,500+ enemies · 330+ dungeons · 33,000+ cards to find · 640+ items.

A full player guide ships with the game (`GAME_GUIDE.md`).

> **Fair warning: this game is HARD — on purpose.** Expect to lose duels, lose territory,
> and claw your way back. That's the design.

## Install & play

1. Install **Java 17 or newer** (64-bit) — https://adoptium.net
2. Download the latest release zip and unzip it anywhere.
3. Run **`The Forsaken Realms.exe`** (or `The Forsaken Realms.cmd`).

Currently **tested on Windows PC only**. The engine itself is cross-platform Java, but other
platforms are untested territory for now.

Saves and settings live in `%APPDATA%\ForsakenRealms`. The card-art cache is shared with a
stock Forge install if you have one, so you never re-download images you already own. A stock
Forge installation on the same machine is otherwise completely unaffected.

## Feedback

This is an early release — balance feedback and bug reports are very welcome on the
**[Discord server](https://discord.gg/TTRPKc9HYJ)**.

## Building from source

This repository is a fork of [Card-Forge/forge](https://github.com/Card-Forge/forge) carrying
the game's engine changes and the world itself
(`forge-gui/res/adventure/The Forsaken Realms/`). To build:

```
mvn -pl forge-gui-mobile-dev -am package -DskipTests
python standalone-packaging/build_standalone.py --zip
```

Development notes live in `MOD_SCOPE.md` (feature list), `MOD_CHANGELOG.md` (engineering log),
and `CORE_ENGINE_CHANGES.md` (every engine file the game modifies).

## Credits & license

Built on **[Forge](https://github.com/Card-Forge/forge)** — the open-source card-game rules engine by
the Card-Forge team and its many contributors. Portions of the world's content are adapted from
the **Realm of Legends** and **Shandalar Old Border** adventure planes, and the world itself
began as a clone of **Shandalar**. See `standalone-packaging/CREDITS.md` for details.

Licensed under the **GNU General Public License v3** (see `LICENSE`).

*The Forsaken Realms is unofficial Fan Content permitted under the Wizards of the Coast Fan
Content Policy. Magic: The Gathering and all card names and images are © Wizards of the Coast, LLC.*
