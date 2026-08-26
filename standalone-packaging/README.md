# The Forsaken Realms

A standalone single-player adventure card game built on the open-source
[Forge](https://github.com/Card-Forge/forge) card-game rules engine.

Explore a living overworld where five mage colors wage a slow war for territory:
capture and rebuild towns, raise your Capitol, manage Wood/Stone/Gold/Shard
economies, build reputations with each color, research new card expansions,
and fight through hundreds of rotating dungeons — all with Forge's full
rules engine underneath. Fair warning: it's HARD, and that's intentional. See `GAME_GUIDE.md` for a complete player guide.

## Requirements

- **Java 17 or newer** (64-bit). Get it from https://adoptium.net if you don't have it.
- Windows (tested on PC only; the engine is cross-platform Java, but other platforms are untested).
- ~2 GB free disk space (more as card art downloads).

## Install & Run

1. Unzip this folder anywhere you like.
2. Run **`The Forgotten Realms.exe`** (or `The Forgotten Realms.cmd` if the exe
   won't start).
3. Pick your race and difficulty, and good luck out there.

That's it — no installer, nothing else touched.

## Where your data lives

- **Saves, settings, decks:** `%APPDATA%\ForgottenRealms`
- **Card art cache:** `%LOCALAPPDATA%\Forge\Cache\pics\cards` — deliberately
  shared with stock Forge, so if you already play Forge you reuse the gigabytes
  of card images you've downloaded instead of fetching them again. If you don't
  have Forge, images simply download there as you encounter cards.

A stock Forge install (any version) on the same machine is otherwise completely
unaffected by this game, and vice versa.

**Moving saves from a Forge-based install of this mod:** copy
`%APPDATA%\Forge\adventure\The Forgotten Realms` into
`%APPDATA%\ForgottenRealms\adventure\`.

## Feedback & Community

This is an early release — feedback on balance and bugs is very welcome!
Join us on Discord: **https://discord.gg/TTRPKc9HYJ**

Enjoying the game? You can support development on Ko-fi:
**https://ko-fi.com/thesaguy**

## Updates

This is a **pinned build** — it never auto-updates, and the stock Forge updater
is disabled on purpose (updating the engine underneath a running world corrupts
the experience). New versions of The Forsaken Realms ship as fresh zips; your
saves in `%APPDATA%\ForgottenRealms` carry over.

## License & Source

Forge — and therefore this game — is licensed under the **GNU General Public
License v3** (see `LICENSE.txt`). The complete modified source code is available
at: **https://github.com/TheSAguy/The-Forgotten-Realms**

See `CREDITS.md` — this game stands on a lot of other people's excellent work.

---

*The Forsaken Realms is unofficial Fan Content permitted under the Wizards of
the Coast Fan Content Policy. Magic: The Gathering and all card names and images
are © Wizards of the Coast, LLC.*
