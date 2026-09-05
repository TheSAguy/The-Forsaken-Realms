# Save editing — reading and writing a Forsaken Realms save

**Read this before touching a `.sav` file.** The method here is the only safe one, and it has been
independently re-derived twice because it was not written down. Third time it is written down.

## The one rule

**Edit saves with Java, against the game's own classes. Never byte-patch from Python.**

A `.sav` is zlib-compressed **Java serialization** (`forge.adventure.util.SaveFileData` wrapping
`java.util.EnumMap` / `forge.deck.CardPool` / `ConcurrentHashMap`). Two consequences:

- **Writing** by patching bytes is not merely hard, it is wrong: Java serialization uses
  back-reference handles, so inserting or resizing anything shifts every later handle and corrupts
  the stream. A 2026-09-01 session talked itself out of save editing entirely on this basis — the
  correct conclusion was "use Java", not "it cannot be done".
- **Reading** by scraping strings *under-reports*. Each unique string is written once and
  back-referenced afterwards, so a naive scrape of a save with 847 distinct cards finds ~401 and
  gives no quantities at all. Read it through `SaveFileData` and you get exact counts.

## Tools in this folder

| File | Purpose |
|---|---|
| `Inspect.java` | **Read-only.** Dumps player stats, every deck slot, and the full collection with counts. Safe to run while the game is open. |
| `BuildDecks2.java` | Writes decklists into deck slots. Dry-run by default; `--write` to apply; takes its own `.bak`. |
| `DumpSave.java` | Lower-level structural dump, for when you need to see keys rather than decks. |
| `WriteDecks.java` | **Generic** deck writer (2026-09-04): `java WriteDecks <save> [--show] [--write] slot=<n>:<Deck Name>:<listfile> ...` - list files are `<count> <Card Name>` lines (`mono_white.txt` etc. here are the round-111 examples); resolves owned printings, adds free unsellable TRK basics to the collection for any shortfall exactly like the editor's Add Basic Lands, dry run by default, `.prededit3.bak`, re-reads and prints every slot after writing. |

## Running them

```bash
S="F:/FORGE/C--Users-vicwaver-MTG-Forge/dev-tools/save-editing"
JAR="F:/FORGE/TFR-Standalone/The Forsaken Realms/forge-gui-mobile-dev-2.0.15-SNAPSHOT-jar-with-dependencies.jar"
SAV="C:/Users/User/AppData/Roaming/ForsakenRealms/adventure/The Forsaken Realms/1_save_slot.sav"

"/c/Program Files/Java/jdk-22/bin/javac" -cp "$JAR" -d "$S" "$S/Inspect.java"
"/c/Program Files/Java/jdk-22/bin/java"  -cp "$JAR;$S" Inspect "$SAV"
```

The classpath is the **shipped game jar** — that is what supplies `SaveFileData`. Any current build
works; the live folder's jar is the convenient one. `GdxNativesLoader.load()` at the top of `main`
is required or libGDX types blow up on first touch.

## Hard prerequisites

1. **The game must be CLOSED before writing.** It rewrites the save on its own schedule (autosave,
   day tick, manual save), so a write while it is open is either clobbered moments later or lands
   mid-write. Check with `ps -W | grep java`, and check the save's mtime.
2. **Always keep the `.bak`.** Both tools write one; do not remove that behaviour.
3. **Dry-run first, every time.** Confirm the decklist resolves and the target slot is what you
   think it is before passing `--write`.

## The write shape (why it is safe)

`BuildDecks2` reads header + `mainData`, pulls the `player` subdata, replaces **only**
`deck_<slot>` / `deck_name_<slot>`, and writes back in exactly the shape `WorldSave.save()`
produces: `Deflater` -> `ObjectOutputStream`, header object then main object. The `world`,
`worldStage` and `pointOfInterestChanges` byte arrays are **copied through verbatim and never
deserialized**, which is what keeps a 10 MB world safe from a deck edit.

## Facts about the save that cost time to learn

- **The collection lives at `player.readObject("cards")`** as a `String[]` of `"<count>
  <Name>|<SET>|[art]"` rows, optionally with a `|#{noSellValue=true}` suffix on granted basics.
  Parse the leading count; split the name on the first `|`.
- **Decks are `String[]` in the same printing format**, under `deck_0` … `deck_9`
  (`deckCount` = 10). `deck_name_<n>` is a plain string, and an unused slot is literally named
  `"Empty Deck"` with a zero-length array.
- **Decks do NOT own their cards.** Verified 2026-09-02: slots 0 and 1 both contained the same
  printings of `Angelic Quartermaster|DBL|[269]`, `Renewed Faith|AKH|[25]`, `Sacred Cat|AKH|[27]`
  and `Wedding Announcement|DBL|[312]`. The collection is a shared pool, so two decks may both use
  a card the player owns one copy of. Do not try to "reserve" cards across decks.
- **`selectedDeckIndex` is the active deck** and writing another slot does not change it — the
  player switches in-game.
- **Deck size convention in this plane is 46 cards** (both authored decks and both generated ones),
  typically 18 lands.

## Verify AFTER writing, not just that the write returned

A successful write proves nothing about a valid save. Re-run `Inspect` and confirm: the save
re-deserializes at all, the target slot holds what you intended, **the other slots are unchanged**,
the collection count is identical, and life/gold/resources are intact.

## History

- **2026-08-29 (round 66)** — method first developed; `BuildDeck.java` wrote "Ninefold Vigil" into
  slot 1. Left only a `.prededit.bak` behind and no documentation.
- **2026-09-02 (round 82)** — method re-derived from scratch after a session wrongly concluded save
  writing was unsafe. Wrote "Dawn Offensive" (slot 2) and "Gallows Procession" (slot 3). Tools
  moved into the repo and documented *here* so this is the last re-derivation.
