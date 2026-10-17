# worldgen-prototype - research bench for the TFR 2 overworld

**Not game code.** A scratch prototype from the 2026-10-17 world-generation research
(`docs/research/procedural-worldgen.md`). It is not part of the Maven build or the game, and it ships nowhere.
It is kept because that doc's proof of concept ports it into a production package.

What it builds, from a seed plus sliders, on a square tile grid (700 or 1000):

- landmasses from warped continent blobs with straits, open-sea islands, sea level by quantile;
- plate-boundary mountain ranges, erosion scaled by **Age** (implicit stream power + thermal slump);
- Priority-Flood + D8 drainage, rivers by discharge (0 dead ends), lakes, inland seas;
- latitude + lapse-rate temperature, westerly rainfall with rain shadow, climate classes;
- **one territory per MTG set** (core sets at 2x area), capacity-balanced multi-source Dijkstra with rivers and
  ridges as costly borders, a themed core per territory, plane realms with a cohesion knob, Hungarian set assignment;
- Universes Beyond isles placed after sea level;
- the **AI start layer** (all C(19,5) core-set combinations scored), an optional player 6th area;
- **harbors and ferry lanes** (sea-Delaunay graph, MST + Urquhart shortcuts), A* roads with bridges and passes;
- a reachability validator over land, bridges and ferries.

Measured (one thread, JDK 22, Ryzen 7 7800X3D, median of 3 warm runs): 1.3-1.7 s per world at 700x700,
2.8 s at 1000x1000. Per-stage timings, fairness and harbor metrics go to `<out>/<preset>_metrics.json`.

## Run

```bash
cd dev-tools/worldgen-prototype
"/c/Program Files/Java/jdk-22/bin/javac" --release 17 -d /tmp/wgp/classes src/*.java
mkdir -p /tmp/wgp/out
"/c/Program Files/Java/jdk-22/bin/java" -cp /tmp/wgp/classes Main /tmp/wgp/out data all
```

Modes: `all` (5 presets + cohesion sweep, about 37 s), `quick`, `sweep`, `only=<key>`. Write output outside the repo.
The core classes use no `java.awt`; only `Render.java` and `Main.java` do (ImageIO, debug renders).

## Files

| File | Purpose |
|---|---|
| `src/WorldGen.java` | terrain, ranges, erosion, coast, isles, climate, hydrology |
| `src/Territories.java` | set territories, themed cores, plane realms, Hungarian assignment |
| `src/AiStarts.java` | the AI start layer and the player area |
| `src/Harbors.java` | docks, sea-Delaunay graph, ferry lanes |
| `src/Roads.java`, `src/Validator.java` | A* roads, bridges, passes; reachability |
| `src/Settings.java`, `src/SetTable.java` | sliders; the set and plane tables |
| `data/sets.tsv` | the 189 editions in TFR's live reward pool: type, date, plane, core flag, fold target |
| `data/planes.tsv`, `data/set_climate.tsv` | 36 plane buckets with climate affinities; per-set overrides |
| `data/make_tables.py` | rebuilds the two tables from `master_typed.tsv` and `reference/BubbleProto.java` |
| `reference/BubbleProto.java` | the second prototype (177 set bubbles), source of the plane table |

Set-to-plane facts come from the mtg.wiki set infoboxes (CC BY-NC-SA 4.0: facts only, no text) and Wikidata P840 (CC0).

## Known artifacts

The ocean share lands 2-4 points under the Water slider (isles are carved after the sea-level cut). The smallest
territory can be about 0.25x the median (no minimum-area reseed yet). AI seats ignore climate (some start on snow),
and AI harbor access is not required. MST ferry lanes can run 250-290 tiles. Old coastal plains grow parallel
straight rivers, and the deep-water blocks are a fixed 8 tiles. The hillshade in the renders is not something the
game's tile renderer can draw: judge the look only from real-autotile sheets.
