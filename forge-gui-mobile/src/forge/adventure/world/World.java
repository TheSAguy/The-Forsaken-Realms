package forge.adventure.world;

import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.TextureData;
import com.badlogic.gdx.graphics.g2d.TextureAtlas;
import com.badlogic.gdx.math.Rectangle;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.Disposable;
import com.badlogic.gdx.utils.Json;
import forge.Forge;
import forge.adventure.data.*;
import forge.adventure.pointofintrest.PointOfInterest;
import forge.adventure.pointofintrest.PointOfInterestMap;
import forge.adventure.scene.Scene;
import forge.adventure.stage.GameHUD;
import forge.adventure.stage.WorldStage;
import forge.adventure.util.Config;
import forge.adventure.util.Current;
import forge.adventure.util.DungeonRotation;
import forge.adventure.util.EconomyBuildings;
import forge.adventure.util.EditionProgression;
import forge.adventure.util.Paths;
import forge.adventure.util.ResourceSpawns;
import forge.adventure.util.SaveFileContent;
import forge.adventure.util.SaveFileData;
import forge.adventure.util.TerritoryControl;
import forge.adventure.util.TownRestoration;
import forge.gui.GuiBase;
import org.apache.commons.lang3.tuple.Pair;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

/**
 * Class that will create the world from the configuration
 */
public class World implements Disposable, SaveFileContent {
    private WorldData data;
    private Pixmap biomeImage;
    private long[][] biomeMap;
    public int[][] terrainMap;
    private static final int collisionBit = 0b10000000000000000000000000000000;
    private static final int isStructureBit = 0b01000000000000000000000000000000;
    private static final int terrainMask = collisionBit | isStructureBit;
    private int width;
    private int height;
    private SpritesDataMap mapObjectIds;
    private PointOfInterestMap mapPoiIds;
    private BiomeTexture[] biomeTexture;
    private long seed;
    private final Random random = new Random();
    private boolean worldDataLoaded = false;
    private Texture globalTexture = null;

    // Fog of war: explored[x][y] is stored in the same raw/image-space orientation as biomeMap's
    // internal array (matches the unflipped x,y loop used to build biomeImage), so it lines up
    // directly with minimap pixel blocks. Gameplay lookups go through isExploredWorld(x,y), which
    // applies the same y-flip World already uses for getBiome()/isColliding().
    private boolean[][] explored;
    private Pixmap fogOfWarPixmap;
    private Pixmap fogTilePixmap;
    private int visionRadius = 3; // half of the original 6 - items will raise this later
    // FoW Stage 2 (2026-08-11 user spec): once 80% of the map is explored, reveal the rest
    // outright. One-shot, persisted - without this flag, an already-100%-explored save would
    // re-trigger (and re-notify) the check every day forever, since the 80% threshold would keep
    // trivially re-passing. See checkFogOfWarStage2().
    private boolean fogOfWarStage2Revealed = false;

    // Day/night cycle: dayProgress is the fraction of the current day elapsed, in [0,1), where
    // 0 = midnight. It only advances via advanceTime(), which WorldStage calls once per frame
    // while the player is on the overworld and not paused/in a dialog - so the clock freezes
    // whenever the player enters a town or dungeon (MapStage) or the game itself is paused.
    // Moved to the new tuning.json 2026-08-14 (user request) - was a hardcoded static final
    // (10*60f, ~10 real minutes/day). See TuningData.java.
    private static float dayLengthSeconds() {
        return Config.instance().getTuningData().dayLengthSeconds;
    }
    // Day = 6am-6pm, night = 6pm-6am (user spec 2026-08-12, set when the day/night terrain life
    // modifier became the first real consumer of isNight() - was 20f/6f while nothing used it).
    private static final float NIGHT_START_HOUR = 18f;
    private static final float NIGHT_END_HOUR = 6f;
    private float dayProgress = 0.375f; // fresh world starts at 09:00
    private int dayCount = 1;

    // Territory Control (MOD_SCOPE.md #7): each of the 5 AI colors independently counts down to
    // its next attempt to send a mage at a nearby neutral town. Keyed by lowercase color name
    // (matches each color biome's own "name" field, e.g. green.json's "name": "green"). This is
    // just the persisted counter itself - TerritoryControl.java owns the actual 2-5 day random
    // range and what happens when a color's count reaches zero. Absent from the map (rather than
    // eagerly seeded for all 5 up front) means "not yet initialized" - lets a save from before
    // this feature existed load with an empty map instead of needing a version check here.
    private final java.util.Map<String, Integer> colorNextAttackDay = new java.util.HashMap<>();

    public Integer getColorNextAttackDay(String color) {
        return colorNextAttackDay.get(color);
    }

    public void setColorNextAttackDay(String color, int day) {
        colorNextAttackDay.put(color, day);
    }

    // Territory Control (MOD_SCOPE.md #7) expansion: each color's current territory radius in
    // tiles, grown over time by TerritoryControl.processTerritoryExpansion() via
    // claimWastelandRing() above. Seeded once (to the same starting radius as the initial
    // neutralizeAfterGeneration() sweep) rather than lazily like colorNextAttackDay - there's no
    // "not yet initialized" state to distinguish here, the starting value is always well-defined.
    private final java.util.Map<String, Integer> colorTerritoryRadius = new java.util.HashMap<>();

    public Integer getColorTerritoryRadius(String color) {
        return colorTerritoryRadius.get(color);
    }

    public void setColorTerritoryRadius(String color, int radiusTiles) {
        colorTerritoryRadius.put(color, radiusTiles);
    }

    // World Standings line-chart history (2026-08-15 user request: "line-chart, number of cities
    // by week for the 6 colors... rolling 10 week window"). One snapshot per real week boundary
    // crossed (see recordStandingsHistoryIfNewWeek(), hooked from WorldStage.onActing() the same
    // place EconomyBuildings/TerritoryControl's own processDaysPassed() already runs), trimmed to
    // the newest STANDINGS_HISTORY_WEEKS entries. standingsHistoryWeeks holds the real week number
    // for each entry (not assumed contiguous - if the player fast-forwards past more than one week
    // between ticks, only the week actually reached gets a snapshot; skipped weeks are simply
    // absent rather than backfilled with guessed data) with standingsHistoryCounts's per-row lists
    // kept parallel to it by index.
    public static final int STANDINGS_HISTORY_WEEKS = 10;
    private final java.util.List<Integer> standingsHistoryWeeks = new java.util.ArrayList<>();
    private final java.util.Map<String, java.util.List<Integer>> standingsHistoryCounts = new java.util.HashMap<>();
    private int standingsHistoryLastWeek = -1;

    public java.util.List<Integer> getStandingsHistoryWeeks() {
        return standingsHistoryWeeks;
    }

    public java.util.Map<String, java.util.List<Integer>> getStandingsHistoryCounts() {
        return standingsHistoryCounts;
    }

    /** Called once per real day-advance (see WorldStage.onActing()) - a no-op unless the week
     *  number has actually gone up since the last recorded entry. */
    public void recordStandingsHistoryIfNewWeek(java.util.Map<String, Integer> currentCounts) {
        int week = (dayCount - 1) / 7;
        if (week == standingsHistoryLastWeek)
            return;
        standingsHistoryLastWeek = week;
        standingsHistoryWeeks.add(week);
        for (java.util.Map.Entry<String, Integer> entry : currentCounts.entrySet()) {
            standingsHistoryCounts
                    .computeIfAbsent(entry.getKey(), k -> new java.util.ArrayList<>())
                    .add(entry.getValue());
        }
        while (standingsHistoryWeeks.size() > STANDINGS_HISTORY_WEEKS) {
            standingsHistoryWeeks.remove(0);
            for (java.util.List<Integer> counts : standingsHistoryCounts.values())
                if (!counts.isEmpty())
                    counts.remove(0);
        }
        System.out.println("[TFR-StandingsHistory] recorded week " + week + " snapshot, "
                + standingsHistoryWeeks.size() + "/" + STANDINGS_HISTORY_WEEKS + " weeks in window");
    }

    // Color Defeat (MOD_SCOPE.md #61, user request 2026-08-14): which of the 5 AI colors have had
    // their castle's boss defeated - TerritoryControl.defeatColor() is the only writer (idempotent,
    // checks this set before applying any consequence). A HashSet, not a per-color boolean map,
    // since "absent" and "false" mean the same thing here and there's no third state to track.
    private final java.util.Set<String> defeatedColors = new java.util.HashSet<>();

    public boolean isColorDefeated(String color) {
        return defeatedColors.contains(color);
    }

    public void setColorDefeated(String color) {
        defeatedColors.add(color);
    }

    public int getDefeatedColorCount() {
        return defeatedColors.size();
    }

    // Color Defeat forced-targeting (user spec 2026-08-14): "the next attack from the two colors
    // either side of the defeated player will be 100% probability to be a player town." One-shot
    // per surviving ally, armed by TerritoryControl.defeatColor() and consumed by the next
    // dispatch() call from that color that finds an actual player-owned target to force (a color
    // with no player towns to attack yet keeps its flag armed rather than wasting it on a normal
    // roll - see dispatch()'s own comment).
    private final java.util.Set<String> forcedPlayerTargetPending = new java.util.HashSet<>();

    public boolean hasForcedPlayerTarget(String color) {
        return forcedPlayerTargetPending.contains(color);
    }

    public void armForcedPlayerTarget(String color) {
        forcedPlayerTargetPending.add(color);
    }

    public void clearForcedPlayerTarget(String color) {
        forcedPlayerTargetPending.remove(color);
    }

    // Color Defeat discoverability (2026-08-15 review finding: the only player-facing signal was
    // one overwritable HUD toast at the moment of defeat, and World Standings never marked a
    // defeated color's row any differently from one that simply hasn't expanded yet). Stamped
    // once, at the same moment setColorDefeated() fires - TerritoryControl.defeatColor() is the
    // only writer, same as defeatedColors itself.
    private final java.util.Map<String, Integer> colorDefeatDay = new java.util.HashMap<>();

    /**
     * Per-color lockout on attacking the player's Capitol: color -> the in-game day that color
     * last DISPATCHED a mage at it (user spec 2026-08-31: "The AI can only target the Player's
     * capitol once a week. From each color. So 5 total attacks per week, 1 per AI player... if the
     * capitol is Targeted, regardless if the mage wins, loses, gets killed, the Capitol can't be
     * selected again from that color for at least 7 days").
     * <p>
     * Stamped at DISPATCH, never at resolution - that is what makes "regardless of outcome" true.
     * A mage spawns at its castle and physically walks to the target over several in-game days,
     * and the player can duel it en route without stopping it, so a resolution-time stamp would
     * let a color re-target the Capitol while its first mage was still walking.
     * <p>
     * Lives on World rather than PointOfInterestChanges (which is per-POI, while this is
     * per-COLOR) or AdventurePlayer (which deliberately survives New Game+). Same shape and same
     * save/load/reset treatment as colorDefeatDay above.
     */
    private final java.util.Map<String, Integer> capitolTargetedDay = new java.util.HashMap<>();

    public void setCapitolTargetedDay(String color, int day) {
        capitolTargetedDay.put(color, day);
    }

    public Integer getCapitolTargetedDay(String color) {
        return capitolTargetedDay.get(color);
    }

    public void setColorDefeatDay(String color, int day) {
        colorDefeatDay.put(color, day);
    }

    public Integer getColorDefeatDay(String color) {
        return colorDefeatDay.get(color);
    }

    // Progressive Set Unlocks (MOD_SCOPE.md #4, editionProgressionEnabled) - which real editions
    // are assigned to each color (+ "neutral") for this save, seeded once by
    // EditionProgression.seedColorShards() during generateNew(). Roaming-monster loot and
    // AI-color-town shops both restrict to a color's shard here; the player's own shops instead
    // use AdventurePlayer.unlockedEditions (grown by research), a separate list entirely.
    private java.util.Map<String, java.util.List<String>> colorEditionShards = new java.util.HashMap<>();

    public java.util.Map<String, java.util.List<String>> getColorEditionShards() {
        return colorEditionShards;
    }

    public void setColorEditionShards(java.util.Map<String, java.util.List<String>> shards) {
        colorEditionShards = shards;
    }

    // Territory Control (MOD_SCOPE.md #7): per-CAPTURED-TOWN territory radius in tiles, keyed by
    // PointOfInterest.getID() - the town-scale analogue of colorTerritoryRadius above. Seeded at
    // RECOLOR_RADIUS when a town is captured (TerritoryControl.onMageArrived() for AI captures,
    // TownRestoration's restore path for the player's), grown daily by
    // TerritoryControl.processTerritoryExpansion() up to TOWN_MAX_TERRITORY_RADIUS (15, per user
    // request 2026-08-08 - "for captured towns, let's have them expand to 15"). A town with no
    // entry never expands - deliberately: world-gen original towns inside a castle's own kept
    // circle were never "captured" and ride their color's castle radius instead. Lazily absent
    // like colorNextAttackDay so saves predating this load as an empty map. NOTE: a captured
    // town's id CHANGES when it's captured again (transformInto() derives getID() from the new
    // data.name), so a recaptured town's old entry simply goes stale/unreachable - harmless, and
    // the new owner seeds a fresh entry at capture.
    private final java.util.Map<String, Integer> townTerritoryRadius = new java.util.HashMap<>();

    public Integer getTownTerritoryRadius(String poiId) {
        return townTerritoryRadius.get(poiId);
    }

    public void setTownTerritoryRadius(String poiId, int radiusTiles) {
        townTerritoryRadius.put(poiId, radiusTiles);
    }

    // Ordinary-town territory growth pacing (2026-08-14 user spec: 1 tile/week, down from the
    // 9-tiles/day rate towns previously shared with AI castles/the Capitol) - a per-day rate can't
    // express "1 tile per 7 days" as a whole number, so this tracks each town's own last-grew day
    // instead (same "accumulate until a threshold, then advance in whole steps" shape as guard
    // salary's lastPaidDay), rather than a fractional accumulator needing its own rounding rules.
    // Same lazy-absent/persistence pattern as townTerritoryRadius above.
    private final java.util.Map<String, Integer> townLastGrowthDay = new java.util.HashMap<>();

    public Integer getTownLastGrowthDay(String poiId) {
        return townLastGrowthDay.get(poiId);
    }

    public void setTownLastGrowthDay(String poiId, int day) {
        townLastGrowthDay.put(poiId, day);
    }

    // Dungeon rotation (MOD_SCOPE.md, user request 2026-08-08): per-POI lifecycle state, all keyed
    // by PointOfInterest.getID() and persisted like the Territory Control maps above. Logic lives
    // in DungeonRotation (util); these are just the timers/counters. poiDespawnDay: the in-game
    // day an eligible, currently-visible dungeon/cave disappears. poiRespawnDay: the day a
    // currently-hidden one reappears. poiFailedAttempts: losses inside a side-quest-targeted
    // dungeon so far (3 strikes and it despawns, per user spec).
    private final java.util.Map<String, Integer> poiDespawnDay = new java.util.HashMap<>();
    private final java.util.Map<String, Integer> poiRespawnDay = new java.util.HashMap<>();
    private final java.util.Map<String, Integer> poiFailedAttempts = new java.util.HashMap<>();
    // Weighted spawn tier system, Layer 3 (2026-08-25 redesign, replacing the original
    // time-decaying suppression-stack system): how many times this exact enemy name has been
    // confirmed-defeated in roaming combat, permanently - never decays over time. Same shape as
    // the poi* maps above (World-level, keyed by a stable string id). Permanence (rather than the
    // old day-based recovery) is required for SpawnTierWeighting.rawSpawnWeight()'s uniform-
    // baseline-halved-per-kill formula to have the property the user specifically asked for:
    // defeat every candidate in a pool an equal number of times and the pool reads as perfectly
    // uniform again, from a stateless recompute rather than a path-dependent redistribution.
    private final java.util.Map<String, Integer> enemyPermanentKillCount = new java.util.HashMap<>();

    public java.util.Map<String, Integer> getPoiDespawnDay() {
        return poiDespawnDay;
    }

    public java.util.Map<String, Integer> getPoiRespawnDay() {
        return poiRespawnDay;
    }

    public java.util.Map<String, Integer> getPoiFailedAttempts() {
        return poiFailedAttempts;
    }

    public java.util.Map<String, Integer> getEnemyPermanentKillCount() {
        return enemyPermanentKillCount;
    }

    // How many rotatable dungeons/caves should be visible at once (pool rotation) - set to
    // 1/POOL_MULTIPLIER of the overprovisioned pool for a new world by
    // DungeonRotation.initializeNewWorld(), or locked to the current visible count on first tick
    // for a save predating the pool. 0 = not yet initialized.
    private int poiActiveTarget = 0;
    // Center Towns (MOD_SCOPE #102): the five star towns' TILE positions, recorded once at world
    // generation and persisted. Position, not POI id, because every capture re-keys the POI
    // (transformInto) - see STAR_TOWNS_RESEARCH.md 1.7. Empty on pre-feature saves = feature inert.
    private final java.util.List<int[]> starTownTiles = new java.util.ArrayList<>();
    public java.util.List<int[]> getStarTownTiles() { return starTownTiles; }
    // Ring Towns (round 99): per (AI color, ring tile) day of the last targeting, persisted with the world.
    private final java.util.Map<String, Integer> ringTargetDays = new java.util.HashMap<>();
    public int ringTargetDay(String color, int tileX, int tileY) {
        Integer d = ringTargetDays.get(color + "|" + tileX + "," + tileY);
        return d == null ? Integer.MIN_VALUE : d;
    }
    public void recordRingTargeted(String color, int tileX, int tileY, int day) {
        ringTargetDays.put(color + "|" + tileX + "," + tileY, day);
    }
    // Round 100 (user spec 2026-09-03): colors whose capital the player has taken (their active-mage cap is
    // halved) and the Ring City tiles the player has visited (+1 max life each while neutral/player-held).
    private final java.util.Set<String> capitolLostColors = new java.util.HashSet<>();
    private final java.util.Set<String> ringVisitedTiles = new java.util.HashSet<>();
    public boolean isCapitolLost(String color) { return color != null && capitolLostColors.contains(color); }
    public void markCapitolLost(String color) { if (color != null) capitolLostColors.add(color); }
    public java.util.Set<String> getCapitolLostColors() { return capitolLostColors; }
    public boolean isRingVisited(int tileX, int tileY) { return ringVisitedTiles.contains(tileX + "," + tileY); }
    public boolean markRingVisited(int tileX, int tileY) { return ringVisitedTiles.add(tileX + "," + tileY); }
    /** Center Towns (MOD_SCOPE #102): ordinary towns (not Spawn, not the star towns themselves) are kept
     *  out of the star's disc - see the placement loop in generateNew(). */
    private static boolean isOrdinaryTownData(PointOfInterestData d) {
        return d != null && "town".equals(d.type) && d.name != null && !"Spawn".equals(d.name) && !d.name.startsWith("Waste Town Center");
    }
    private static int starTownExclusionRadius() {
        int r = Config.instance().getTuningData().starTownExclusionRadiusTiles;
        return r > 0 ? r : 24;
    }
    /** Round 98 (user spec 2026-09-03): ordinary towns keep TuningData.townMinSpacingTiles from every town placed so far. */
    private boolean tooCloseToPlacedTown(List<PointOfInterest> placed, double x, double y) {
        int min = Config.instance().getTuningData().townMinSpacingTiles;
        int ringMin = Math.max(min, Config.instance().getTuningData().ringCityTownExclusionTiles); // round 102
        if (min <= 0 && ringMin <= 0)
            return false;
        for (PointOfInterest t : placed) {
            boolean ring = t.getData() != null && t.getData().name != null && t.getData().name.contains(" Town Center");
            int limit = ring ? ringMin : min;
            if (limit > 0 && Math.hypot(x - t.getPosition().x / data.tileSize, y - t.getPosition().y / data.tileSize) < limit)
                return true;
        }
        return false;
    }
    /**
     * Round 99 (user spec 2026-09-03): ids of the towns/capitals reachable by road from the tile
     * (startTileX, startTileY). Flood fill over road-bit tiles (8-neighborhood); a town counts as
     * reached when the flood touches any tile within two tiles of it, and the flood then also continues
     * from the road tiles around that town (world-gen roads may stop at a town's edge). Used by
     * TerritoryControl.connectCapturedTownByRoad to link a new holding to the closest town that is
     * already road-connected to the owner's capital.
     */
    public java.util.Set<String> roadConnectedTownIds(int startTileX, int startTileY) {
        java.util.Set<String> reached = new java.util.HashSet<>();
        long roadBit = 1L << data.GetBiomes().size();
        int w = getWidthInTiles(), h = getHeightInTiles(), ts = getTileSize();
        java.util.Map<Long, PointOfInterest> townAt = new java.util.HashMap<>();
        for (PointOfInterest poi : getAllPointOfInterest()) {
            String type = poi.getData() == null ? null : poi.getData().type;
            if (!"town".equals(type) && !"capital".equals(type))
                continue;
            int tx = (int) (poi.getPosition().x / ts), ty = (int) (poi.getPosition().y / ts);
            townAt.put(((long) tx << 32) | (ty & 0xffffffffL), poi);
        }
        boolean[][] seen = new boolean[w][h];
        java.util.ArrayDeque<int[]> queue = new java.util.ArrayDeque<>();
        java.util.function.BiConsumer<Integer, Integer> seed = (sx, sy) -> {
            for (int nx = sx - 2; nx <= sx + 2; nx++)
                for (int ny = sy - 2; ny <= sy + 2; ny++)
                    if (nx >= 0 && ny >= 0 && nx < w && ny < h && !seen[nx][ny] && (getBiome(nx, ny) & roadBit) != 0) {
                        seen[nx][ny] = true;
                        queue.add(new int[]{nx, ny});
                    }
        };
        seed.accept(startTileX, startTileY);
        while (!queue.isEmpty()) {
            int[] t = queue.poll();
            for (int nx = t[0] - 2; nx <= t[0] + 2; nx++) {
                for (int ny = t[1] - 2; ny <= t[1] + 2; ny++) {
                    PointOfInterest town = townAt.get(((long) nx << 32) | (ny & 0xffffffffL));
                    if (town != null && reached.add(town.getID()))
                        seed.accept(nx, ny);
                }
            }
            for (int nx = t[0] - 1; nx <= t[0] + 1; nx++)
                for (int ny = t[1] - 1; ny <= t[1] + 1; ny++)
                    if (nx >= 0 && ny >= 0 && nx < w && ny < h && !seen[nx][ny] && (getBiome(nx, ny) & roadBit) != 0) {
                        seen[nx][ny] = true;
                        queue.add(new int[]{nx, ny});
                    }
        }
        return reached;
    }
    /** Round 100: Ring Cities and Spawn are exempt from the world-gen link rules (their star roads are explicit). */
    private static boolean isRingOrSpawnTown(PointOfInterest t) {
        PointOfInterestData d = t.getData();
        return d != null && d.name != null && ("Spawn".equals(d.name) || d.name.contains(" Town Center"));
    }
    private static boolean roadLinkFull(List<PointOfInterest> towns, int[] degree, int idx, int maxLinks) {
        return !isRingOrSpawnTown(towns.get(idx)) && degree[idx] >= maxLinks;
    }
    private static void countRoadLink(List<PointOfInterest> towns, int[] degree, boolean[] anyLink, int a, int b) {
        anyLink[a] = true;
        anyLink[b] = true;
        if (!isRingOrSpawnTown(towns.get(a)) && !isRingOrSpawnTown(towns.get(b))) {
            degree[a]++;
            degree[b]++;
        }
    }
    private void recordStarTowns() {
        starTownTiles.clear();
        for (PointOfInterest poi : getAllPointOfInterest()) {
            if (poi.getData() == null || poi.getData().name == null || !poi.getData().name.startsWith("Waste Town Center"))
                continue;
            starTownTiles.add(new int[]{(int) (poi.getPosition().x / getTileSize()), (int) (poi.getPosition().y / getTileSize())});
        }
        System.out.println("[TFR-StarTowns] recorded " + starTownTiles.size() + " Center Town(s) at generation");
    }

    public int getPoiActiveTarget() {
        return poiActiveTarget;
    }

    public void setPoiActiveTarget(int target) {
        poiActiveTarget = target;
    }

    public boolean isDungeonRotationEnabled() {
        ConfigData configData = Config.instance().getConfigData();
        return configData != null && configData.dungeonRotationEnabled;
    }

    // Side-quest timers (user request 2026-08-08, QuestExpiry.java): the in-game day each active
    // quest's 30-day clock started, keyed by String.valueOf(quest.getID()). Kept OUTSIDE
    // AdventureQuestData deliberately - that class is Java-serialized into saves WITHOUT a
    // serialVersionUID, so adding a field there would break every existing save's quest list.
    // Stamped lazily by QuestExpiry's daily tick (a quest first seen by the tick starts its clock
    // then - at most one day of slack, and pre-feature saves' quests get a full fresh window).
    private final java.util.Map<String, Integer> questAcceptedDay = new java.util.HashMap<>();

    public java.util.Map<String, Integer> getQuestAcceptedDay() {
        return questAcceptedDay;
    }

    // Random resource spawns (MOD_SCOPE.md, user request 2026-08-08): up to
    // ResourceSpawns.MAX_SPAWNS pickups scattered on the overworld, each an int[] of
    // {tileX, tileY, type, value, expiryDay} in world tile space. All spawn/expiry/pickup LOGIC
    // lives in ResourceSpawns (util) - this is just the persisted state, same save/load pattern
    // as the Territory Control maps above. The seeded flag distinguishes "never populated" (seed
    // 20 on first tick) from "legitimately below 20 mid-game" (player picked some up; the daily
    // tick tops the pool back up).
    private final List<int[]> resourceSpawns = new ArrayList<>();
    private boolean resourceSpawnsSeeded = false;

    public List<int[]> getResourceSpawns() {
        return resourceSpawns;
    }

    public boolean isResourceSpawnsSeeded() {
        return resourceSpawnsSeeded;
    }

    public void setResourceSpawnsSeeded(boolean seeded) {
        resourceSpawnsSeeded = seeded;
    }

    public Random getRandom() {
        return random;
    }

    static public int highestBiome(long biome) {
        return (int) (Math.log(Long.highestOneBit(biome)) / Math.log(2));
    }

    public boolean collidingTile(Rectangle boundingRect) {

        int xLeft = (int) boundingRect.getX() / getTileSize();
        int yTop = (int) boundingRect.getY() / getTileSize();
        int xRight = (int) ((boundingRect.getX() + boundingRect.getWidth()) / getTileSize());
        int yBottom = (int) ((boundingRect.getY() + boundingRect.getHeight()) / getTileSize());

        if (isColliding(xLeft, yTop))
            return true;
        if (isColliding(xLeft, yBottom))
            return true;
        if (isColliding(xRight, yBottom))
            return true;
        if (isColliding(xRight, yTop))
            return true;

        return false;
    }

    public void loadWorldData() {
        if (worldDataLoaded)
            return;

        FileHandle handle = Config.instance().getFile(Paths.WORLD);
        String rawJson = handle.readString();
        this.data = (new Json()).fromJson(WorldData.class, rawJson);
        biomeTexture = new BiomeTexture[data.GetBiomes().size() + 1];

        int biomeIndex = 0;
        for (BiomeData biome : data.GetBiomes()) {

            biomeTexture[biomeIndex] = new BiomeTexture(biome, data.tileSize);
            biomeIndex++;
        }
        biomeTexture[biomeIndex] = new BiomeTexture(data.roadTileset, data.tileSize);
        worldDataLoaded = true;
    }

    @Override
    public void load(SaveFileData saveFileData) {

        if (biomeImage != null)
            biomeImage.dispose();

        loadWorldData();

        // World is a process-lifetime singleton (WorldSave.currentSave), reused across every
        // save load/new game within one app run - reset here for the same reason generateNew()
        // resets dayCount/colorTerritoryRadius/structureSwapCache below (adversarial review
        // 2026-08-13: this lazy cache was left out of both reset points, so loading a second,
        // different-land save in the same session kept computing the fully-explored percentage
        // against the FIRST save's land-tile count).
        cachedLandTileTotal = -1;

        biomeImage = saveFileData.readPixmap("biomeImage");
        biomeMap = (long[][]) saveFileData.readObject("biomeMap");
        terrainMap = (int[][]) saveFileData.readObject("terrainMap");


        width = saveFileData.readInt("width");
        height = saveFileData.readInt("height");
        mapObjectIds = new SpritesDataMap(getChunkSize(), this.data.tileSize, this.data.width / getChunkSize());
        mapObjectIds.load(saveFileData.readSubData("mapObjectIds"));
        mapPoiIds = new PointOfInterestMap(getChunkSize(), this.data.tileSize, this.data.width / getChunkSize(), this.data.height / getChunkSize());
        mapPoiIds.load(saveFileData.readSubData("mapPoiIds"));
        seed = saveFileData.readLong("seed");

        Object exploredObj = saveFileData.readObject("explored");
        if (exploredObj instanceof boolean[][] && ((boolean[][]) exploredObj).length == width) {
            explored = (boolean[][]) exploredObj;
        } else {
            // Save predates fog of war (or dimensions don't match) - default to fully revealed
            // rather than retroactively fogging a save that was made without this feature.
            explored = new boolean[width][height];
            for (boolean[] row : explored) Arrays.fill(row, true);
        }
        rebuildFogOfWarPixmap();

        // Saves predating the day/night cycle simply don't have these keys - readFloat/readInt
        // default to 0, so fall back to the same fresh-world start used by the field initializers.
        dayProgress = saveFileData.containsKey("dayProgress") ? saveFileData.readFloat("dayProgress") : 0.375f;
        dayCount = saveFileData.containsKey("dayCount") ? saveFileData.readInt("dayCount") : 1;
        fogOfWarStage2Revealed = saveFileData.containsKey("fogOfWarStage2Revealed") && saveFileData.readBool("fogOfWarStage2Revealed");

        colorNextAttackDay.clear();
        if (saveFileData.containsKey("colorNextAttackDay")) {
            //noinspection unchecked
            colorNextAttackDay.putAll((java.util.Map<String, Integer>) saveFileData.readObject("colorNextAttackDay"));
        }

        colorTerritoryRadius.clear();
        if (saveFileData.containsKey("colorTerritoryRadius")) {
            //noinspection unchecked
            colorTerritoryRadius.putAll((java.util.Map<String, Integer>) saveFileData.readObject("colorTerritoryRadius"));
        }

        defeatedColors.clear();
        if (saveFileData.containsKey("defeatedColors")) {
            //noinspection unchecked
            defeatedColors.addAll((java.util.Set<String>) saveFileData.readObject("defeatedColors"));
        }

        forcedPlayerTargetPending.clear();
        if (saveFileData.containsKey("forcedPlayerTargetPending")) {
            //noinspection unchecked
            forcedPlayerTargetPending.addAll((java.util.Set<String>) saveFileData.readObject("forcedPlayerTargetPending"));
        }

        colorDefeatDay.clear();
        if (saveFileData.containsKey("colorDefeatDay")) {
            //noinspection unchecked
            colorDefeatDay.putAll((java.util.Map<String, Integer>) saveFileData.readObject("colorDefeatDay"));
        }

        // Absent key = empty map = no color on cooldown, which is the correct migration for a save
        // written before this existed.
        capitolTargetedDay.clear();
        if (saveFileData.containsKey("capitolTargetedDay")) {
            //noinspection unchecked
            capitolTargetedDay.putAll((java.util.Map<String, Integer>) saveFileData.readObject("capitolTargetedDay"));
        }

        townTerritoryRadius.clear();
        if (saveFileData.containsKey("townTerritoryRadius")) {
            //noinspection unchecked
            townTerritoryRadius.putAll((java.util.Map<String, Integer>) saveFileData.readObject("townTerritoryRadius"));
        }

        townLastGrowthDay.clear();
        if (saveFileData.containsKey("townLastGrowthDay")) {
            //noinspection unchecked
            townLastGrowthDay.putAll((java.util.Map<String, Integer>) saveFileData.readObject("townLastGrowthDay"));
        }

        colorEditionShards.clear();
        if (saveFileData.containsKey("colorEditionShards")) {
            //noinspection unchecked
            colorEditionShards.putAll((java.util.Map<String, java.util.List<String>>) saveFileData.readObject("colorEditionShards"));
        }

        standingsHistoryWeeks.clear();
        standingsHistoryCounts.clear();
        standingsHistoryLastWeek = -1;
        if (saveFileData.containsKey("standingsHistoryWeeks")) {
            //noinspection unchecked
            standingsHistoryWeeks.addAll((java.util.List<Integer>) saveFileData.readObject("standingsHistoryWeeks"));
            //noinspection unchecked
            standingsHistoryCounts.putAll((java.util.Map<String, java.util.List<Integer>>) saveFileData.readObject("standingsHistoryCounts"));
            if (!standingsHistoryWeeks.isEmpty())
                standingsHistoryLastWeek = standingsHistoryWeeks.get(standingsHistoryWeeks.size() - 1);
        }

        resourceSpawns.clear();
        if (saveFileData.containsKey("resourceSpawns")) {
            //noinspection unchecked
            resourceSpawns.addAll((List<int[]>) saveFileData.readObject("resourceSpawns"));
        }
        resourceSpawnsSeeded = saveFileData.containsKey("resourceSpawnsSeeded") && saveFileData.readInt("resourceSpawnsSeeded") != 0;
        ResourceSpawns.forceResync(); // actors on WorldStage must rebuild from this loaded state

        poiDespawnDay.clear();
        if (saveFileData.containsKey("poiDespawnDay")) {
            //noinspection unchecked
            poiDespawnDay.putAll((java.util.Map<String, Integer>) saveFileData.readObject("poiDespawnDay"));
        }
        poiRespawnDay.clear();
        if (saveFileData.containsKey("poiRespawnDay")) {
            //noinspection unchecked
            poiRespawnDay.putAll((java.util.Map<String, Integer>) saveFileData.readObject("poiRespawnDay"));
        }
        poiFailedAttempts.clear();
        if (saveFileData.containsKey("poiFailedAttempts")) {
            //noinspection unchecked
            poiFailedAttempts.putAll((java.util.Map<String, Integer>) saveFileData.readObject("poiFailedAttempts"));
        }
        enemyPermanentKillCount.clear();
        if (saveFileData.containsKey("enemyPermanentKillCount")) {
            //noinspection unchecked
            enemyPermanentKillCount.putAll((java.util.Map<String, Integer>) saveFileData.readObject("enemyPermanentKillCount"));
        }
        poiActiveTarget = saveFileData.containsKey("poiActiveTarget") ? saveFileData.readInt("poiActiveTarget") : 0;
        capitolLostColors.clear();
        if (saveFileData.containsKey("capitolLostColors"))
            for (String c : saveFileData.readString("capitolLostColors").split(";"))
                if (!c.trim().isEmpty())
                    capitolLostColors.add(c.trim());
        ringVisitedTiles.clear();
        if (saveFileData.containsKey("ringVisitedTiles"))
            for (String t : saveFileData.readString("ringVisitedTiles").split(";"))
                if (!t.trim().isEmpty())
                    ringVisitedTiles.add(t.trim());
        ringTargetDays.clear();
        if (saveFileData.containsKey("ringTargetDays")) {
            for (String entry : saveFileData.readString("ringTargetDays").split(";")) {
                int eq = entry.lastIndexOf('=');
                if (eq > 0)
                    try { ringTargetDays.put(entry.substring(0, eq), Integer.parseInt(entry.substring(eq + 1).trim())); }
                    catch (NumberFormatException ignored) { }
            }
        }
        starTownTiles.clear();
        if (saveFileData.containsKey("starTownTiles")) {
            for (String pair : saveFileData.readString("starTownTiles").split(";")) {
                String[] xy = pair.split(",");
                if (xy.length == 2)
                    try { starTownTiles.add(new int[]{Integer.parseInt(xy[0].trim()), Integer.parseInt(xy[1].trim())}); }
                    catch (NumberFormatException ignored) { }
            }
        }
        questAcceptedDay.clear();
        if (saveFileData.containsKey("questAcceptedDay")) {
            //noinspection unchecked
            questAcceptedDay.putAll((java.util.Map<String, Integer>) saveFileData.readObject("questAcceptedDay"));
        }
        // rebuildPlayerTownVision() is deliberately NOT called here: WorldSave.load() loads this
        // World BEFORE pointOfInterestChanges, and the rebuild reads town-ownership flags from
        // pointOfInterestChanges - calling it now would cache the PREVIOUS session's ownership.
        // WorldSave.load() calls it once both halves are loaded.

        // Repair generic "Waste Town ..." display names left behind by the name-pool drain bug
        // (see BiomeData.getNewTownName()); no-ops on stock planes and on already-repaired saves.
        TownRestoration.migrateGenericTownNames(this);
        // Repair any color missing its capital (worlds generated before the placement
        // safeguards); idempotent, inert unless territoryControlEnabled.
        TerritoryControl.repairMissingCapitals(this);
    }

    @Override
    public SaveFileData save() {

        SaveFileData data = new SaveFileData();

        data.store("biomeImage", biomeImage);
        data.storeObject("biomeMap", biomeMap);
        data.storeObject("terrainMap", terrainMap);
        data.store("width", width);
        data.store("height", height);
        data.store("mapObjectIds", mapObjectIds.save());
        data.store("mapPoiIds", mapPoiIds.save());
        data.store("seed", seed);
        data.storeObject("explored", explored);
        data.store("dayProgress", dayProgress);
        data.store("dayCount", dayCount);
        data.store("fogOfWarStage2Revealed", fogOfWarStage2Revealed);
        data.storeObject("colorTerritoryRadius", colorTerritoryRadius);
        data.storeObject("defeatedColors", defeatedColors);
        data.storeObject("forcedPlayerTargetPending", forcedPlayerTargetPending);
        data.storeObject("colorDefeatDay", colorDefeatDay);
        data.storeObject("capitolTargetedDay", capitolTargetedDay);
        data.storeObject("townTerritoryRadius", townTerritoryRadius);
        data.storeObject("townLastGrowthDay", townLastGrowthDay);
        data.storeObject("colorEditionShards", colorEditionShards);
        data.storeObject("standingsHistoryWeeks", new ArrayList<>(standingsHistoryWeeks));
        data.storeObject("standingsHistoryCounts", standingsHistoryCounts);
        data.storeObject("resourceSpawns", new ArrayList<>(resourceSpawns));
        data.store("resourceSpawnsSeeded", resourceSpawnsSeeded ? 1 : 0);
        data.storeObject("poiDespawnDay", poiDespawnDay);
        data.storeObject("poiRespawnDay", poiRespawnDay);
        data.storeObject("poiFailedAttempts", poiFailedAttempts);
        data.storeObject("enemyPermanentKillCount", enemyPermanentKillCount);
        data.store("poiActiveTarget", poiActiveTarget);
        StringBuilder star = new StringBuilder();
        for (int[] t : starTownTiles)
            star.append(star.length() == 0 ? "" : ";").append(t[0]).append(',').append(t[1]);
        data.store("starTownTiles", star.toString());
        StringBuilder ring = new StringBuilder();
        for (java.util.Map.Entry<String, Integer> re : ringTargetDays.entrySet())
            ring.append(ring.length() == 0 ? "" : ";").append(re.getKey()).append('=').append(re.getValue());
        data.store("ringTargetDays", ring.toString());
        data.store("capitolLostColors", String.join(";", capitolLostColors));
        data.store("ringVisitedTiles", String.join(";", ringVisitedTiles));
        data.storeObject("questAcceptedDay", questAcceptedDay);
        data.storeObject("colorNextAttackDay", colorNextAttackDay);
        return data;
    }


    public BiomeSpriteData getObject(int id) {
        return mapObjectIds.get(id);
    }

    private static class DrawingInformation {

        private int neighbors;
        private final BiomeTexture regions;
        private final int terrain;

        public DrawingInformation(int neighbors, BiomeTexture regions, int terrain) {

            this.neighbors = neighbors;
            this.regions = regions;
            this.terrain = terrain;
        }

        public void draw(Pixmap drawingPixmap) {
            regions.drawPixmapOn(terrain, neighbors, drawingPixmap);
        }
    }

    public Pixmap getBiomeSprite(int x, int y) {
        if (x < 0 || y <= 0 || x >= width || y > height)
            return new Pixmap(data.tileSize, data.tileSize, Pixmap.Format.RGBA8888);
        if (!isExploredWorld(x, y))
            return getFogTile();
        Pixmap real = generateBiomeSprite(x, y);
        if (isFogOfWarEnabled() && !isCurrentlyVisible(x, y))
            return hazeTile(real);
        return real;
    }

    // The tile's true appearance, ignoring fog entirely - callers go through getBiomeSprite(),
    // which decides whether to show this, a hazed copy of it (known but not currently visible),
    // or the black fog tile (never explored).
    private Pixmap generateBiomeSprite(int x, int y) {
        long biomeIndex = getBiome(x, y);
        int biomeTerrain = getTerrainIndex(x, y);
        Pixmap drawingPixmap = new Pixmap(data.tileSize, data.tileSize, Pixmap.Format.RGBA8888);
        ArrayList<DrawingInformation> information = new ArrayList<>();
        for (int i = 0; i < biomeTexture.length; i++) {
            if ((biomeIndex & 1L << i) == 0) {
                continue;
            }
            BiomeTexture regions = biomeTexture[i];
            if (x <= 0 || y <= 1 || x >= width - 1 || y >= height)//edge
            {
                return regions.getPixmap(biomeTerrain);
            }


            int neighbors = 0b000_000_000;

            int bitIndex = 8;
            for (int ny = 1; ny > -2; ny--) {
                for (int nx = -1; nx < 2; nx++) {
                    long otherBiome = getBiome(x + nx, y + ny);
                    int otherTerrain = getTerrainIndex(x + nx, y + ny);


                    if ((otherBiome & 1L << i) != 0 && (biomeTerrain == otherTerrain) | biomeTerrain == 0)
                        neighbors |= (1 << bitIndex);

                    bitIndex--;
                }
            }
            if (biomeTerrain != 0 && neighbors != 0b111_111_111) {
                bitIndex = 8;
                int baseNeighbors = 0;
                for (int ny = 1; ny > -2; ny--) {
                    for (int nx = -1; nx < 2; nx++) {
                        if ((getBiome(x + nx, y + ny) & (1L << i)) != 0)
                            baseNeighbors |= (1 << bitIndex);
                        bitIndex--;
                    }
                }
                information.add(new DrawingInformation(baseNeighbors, regions, 0));
            }
            information.add(new DrawingInformation(neighbors, regions, biomeTerrain));

        }
        int lastFullNeighbour = -1;
        int counter = 0;
        for (DrawingInformation info : information) {
            if (info.neighbors == 0b111_111_111)
                lastFullNeighbour = counter;
            counter++;

        }
        counter = 0;
        if (lastFullNeighbour < 0 && information.size() != 0)
            information.get(0).neighbors = 0b111_111_111;
        for (DrawingInformation info : information) {
            if (counter < lastFullNeighbour) {
                counter++;
                continue;
            }
            info.draw(drawingPixmap);
        }
        return drawingPixmap;

    }

    public int getTerrainIndex(int x, int y) {
        try {
            return terrainMap[x][height - y - 1] & ~terrainMask;
        } catch (ArrayIndexOutOfBoundsException e) {
            return 0;
        }
    }

    public long getBiomeMapXY(int x, int y) {
        try {
            return biomeMap[x][height - y - 1] & (~(0b1 << data.GetBiomes().size()));
        } catch (ArrayIndexOutOfBoundsException e) {
            return biomeMap[biomeMap.length - 1][biomeMap[biomeMap.length - 1].length - 1];
        }
    }

    public boolean isStructure(int x, int y) {
        try {
            return (terrainMap[x][height - y - 1] & ~isStructureBit) != 0;
        } catch (ArrayIndexOutOfBoundsException e) {
            return false;
        }
    }

    public long getBiome(int x, int y) {
        try {
            return biomeMap[x][height - y - 1];
        } catch (ArrayIndexOutOfBoundsException e) {
            return biomeMap[biomeMap.length - 1][biomeMap[biomeMap.length - 1].length - 1];
        }
    }

    public boolean isColliding(int x, int y) {
        try {
            return (terrainMap[x][height - y - 1] & collisionBit) != 0;
        } catch (ArrayIndexOutOfBoundsException e) {
            return true;
        }
    }

    public WorldData getData() {
        return data;
    }

    // Placement priority for generateNew()'s POI loop: lower places earlier. Essentials (one-of
    // buildings the world is broken without) get first pick of the map, towns next (capitals are
    // promoted from them - see TerritoryControl.ensureCapital()), the bulk (5x-overprovisioned
    // rotatable dungeons included) last.
    private static int poiPlacementPriority(PointOfInterestData poi) {
        if (isEssentialPoi(poi))
            return 0;
        if (poi.type != null && poi.type.equals("town"))
            return 1;
        return 2;
    }

    // A POI the world must not generate without: castles/capitals (Territory Control anchors -
    // a color with no castle never expands or attacks; ensureCapital() needs the capital data),
    // Spawn, and story/quest-scripted locations. Used both for placement ordering and for the
    // no-silent-drop rerun below.
    private static boolean isEssentialPoi(PointOfInterestData poi) {
        if (poi.type != null && (poi.type.equals("castle") || poi.type.equals("capital")))
            return true;
        if (poi.name != null && (poi.name.equals("Spawn") || poi.name.startsWith("Quest_")))
            return true;
        if (poi.questTags != null)
            for (String tag : poi.questTags)
                if ("Story".equals(tag))
                    return true;
        return false;
    }

    private void clearTerrain(int x, int y, int size) {

        for (int xclear = -size; xclear < size; xclear++)
            for (int yclear = -size; yclear < size; yclear++) {
                try {
                    terrainMap[x + xclear][height - 1 - (y + yclear)] = 0;
                } catch (ArrayIndexOutOfBoundsException ignored) {}
            }
    }

    private long measureGenerationTime(String msg, long lastTime) {
        long currentTime = System.currentTimeMillis();
        System.out.println(msg + " :\t\t" + ((currentTime - lastTime) / 1000f) + " s");
        return currentTime;
    }

    public boolean generateNew(long seed) {
        try {
            if (GuiBase.isMobile())
                GuiBase.getInterface().preventSystemSleep(true);
            final long[] currentTime = {System.currentTimeMillis()};
            long startTime = System.currentTimeMillis();

            loadWorldData();
//////////////////
///////// initialize
//////////////////

            if (seed == 0) {
                seed = random.nextLong();
            }
            this.seed = seed;
            random.setSeed(seed);
            OpenSimplexNoise noise = new OpenSimplexNoise(seed);

            float noiseZoom = data.noiseZoomBiome;
            width = data.width;
            height = data.height;
            //save at all data
            biomeMap = new long[width][height];
            terrainMap = new int[width][height];
            explored = new boolean[width][height]; // brand new world: nothing explored yet
            cachedLandTileTotal = -1; // new seed -> different land/ocean split, see load()'s own comment
            structureSwapCache = null; // don't inherit a previous game's random structure picks
            nativeStructurePatternCache.clear(); // same reasoning - a new seed needs fresh patterns
            colorlessRedirectStructureCache.clear(); // same reasoning
            // WorldSave.currentSave (and this World instance with it) is a singleton constructed
            // once per app run, not recreated per game - starting a new game without restarting the
            // app reuses the SAME World object, so anything only ever reset inside load() (never
            // here) silently carries over from whatever the previous game session left it at. Real,
            // reported bug: a fresh game started on day 31 because the previous save had reached day
            // 31 before the player returned to the main menu and started over. colorTerritoryRadius
            // is just as load-bearing to reset - a stale, much-larger-than-CASTLE_KEEP_RADIUS_TILES
            // value there would make the very first daily expansion tick claim a huge annulus in one
            // shot instead of growing gradually from the real starting radius.
            dayProgress = 0.375f; // fresh world starts at 09:00, same default load() falls back to
            dayCount = 1;
            colorNextAttackDay.clear();
            colorTerritoryRadius.clear();
            defeatedColors.clear();
            forcedPlayerTargetPending.clear();
            colorDefeatDay.clear();
            capitolTargetedDay.clear();
            townTerritoryRadius.clear();
            townLastGrowthDay.clear();
            colorEditionShards.clear(); // fresh world re-shards editions in generateNew(), not a stale split
            standingsHistoryWeeks.clear();
            standingsHistoryCounts.clear();
            standingsHistoryLastWeek = -1; // fresh world has no history yet, same reasoning as above
            playerTownVisionAreas.clear(); // fresh world, no owned towns yet
            resourceSpawns.clear();
            resourceSpawnsSeeded = false; // fresh world reseeds its 20 on the first tick
            // 2026-09-02 review finding: the one-shot full-map reveal flag survived into a New Game
            // or New Game+ started from a finished run, so the reveal could never fire again.
            fogOfWarStage2Revealed = false;
            ResourceSpawns.forceResync();
            poiDespawnDay.clear();
            poiRespawnDay.clear();
            poiFailedAttempts.clear();
            // Weighted spawn tier system, Layer 3 (2026-08-23, redesigned 2026-08-25) - must be
            // cleared here same as the poi* maps above: New Game+ reuses this exact World instance
            // and calls generateNew() in place rather than constructing a fresh one, so without
            // this an enemy's permanent kill count from the PREVIOUS playthrough would silently
            // carry into the new one.
            enemyPermanentKillCount.clear();
            poiActiveTarget = 0; // initializeNewWorld() sets it once the pool is placed
            starTownTiles.clear();
            ringTargetDays.clear();
            capitolLostColors.clear();
            ringVisitedTiles.clear();
            questAcceptedDay.clear();

            for (int x = 0; x < width; x++) {
                for (int y = 0; y < height; y++) {
                    biomeMap[x][y] = 0;
                    terrainMap[x][y] = 0;
                }
            }

            final int[] biomeIndex = {-1};
            currentTime[0] = measureGenerationTime("loading data", currentTime[0]);
            Map<BiomeStructureData, BiomeStructure> structureDataMap = new ConcurrentHashMap<>();

//////////////////
///////// calculation structure position with wavefunctioncollapse
//////////////////
            List<CompletableFuture<Long>> futures = new ArrayList<>();
            for (BiomeData biome : data.GetBiomes()) {
                if (biome.structures != null) {
                    int biomeWidth = (int) Math.round(biome.width * (double) width);
                    int biomeHeight = (int) Math.round(biome.height * (double) height);
                    for (BiomeStructureData data : biome.structures) {
                        long localSeed = seed;
                        futures.add(CompletableFuture.supplyAsync(()-> {
                            long threadStartTime = System.currentTimeMillis();
                            BiomeStructure structure = new BiomeStructure(data, localSeed, biomeWidth, biomeHeight);
                            try {
                                structure.initialize();
                            } catch (Exception ex) {
                                // Below, the main thread busy-waits on structureDataMap.containsKey(data)
                                // for every structure - if initialize() throws before the put() that used
                                // to be the only one, that key never appears and world-gen hangs forever
                                // instead of failing loudly. Hit for real by Territory Control's shrunk
                                // castle territories (MOD_SCOPE.md #7): a small enough biome region can
                                // make BiomeStructure.initialize() carve out a WFC chunk smaller than the
                                // pattern size, which throws inside OverlappingModel.graphics(). Still
                                // register the (partially-initialized, harmless) structure so the wait
                                // below can proceed - this biome's decorative structures just come out
                                // sparse/incomplete rather than hanging the whole game.
                                ex.printStackTrace();
                            }
                            structureDataMap.put(data, structure);
                            return measureGenerationTime("wavefunctioncollapse " + data.sourcePath, threadStartTime);
                        }));
                    }
                }
            }
            CompletableFuture<?>[] futuresArray = futures.toArray(new CompletableFuture<?>[0]);
            CompletableFuture.allOf(futuresArray).join();
            futures.clear();

            // Generate-as-wasteland redesign v2 (MOD_SCOPE.md #7, spatially-aware placement -
            // replaces the previous round's whole-biome content swap, see MOD_CHANGELOG.md for why:
            // that swap made the swept-away area genuinely wasteland-native, but meant the *kept*
            // circle around each castle also generated using wasteland's content, needing a
            // post-hoc reconstruction that could only sample a small window of a WFC pattern and
            // came out visibly less dense than real, natively-generated territory). Pass B below
            // (after POI placement, once real castle positions are known) redirects any AI-color
            // tile outside that color's real castle radius to a per-color clone of colorless's own
            // structures[], content-identical but sized to that color's own biome width/height (not
            // colorless's - querying colorless's own, differently-scaled pattern instead would leave
            // roughly the outer 50 tiles of each color's territory with zero structures, verified
            // during planning). Building that clone used to happen right here, inline, into a local
            // map - now shared with claimWastelandRing() (daily territory expansion, MOD_SCOPE.md #7
            // follow-up: expansion had this exact same reskin-density limitation, never fixed when
            // Pass B first fixed it for the initial circle) via buildColorlessRedirectStructuresBlocking(),
            // which lazily builds and caches the same thing on a persistent field instead.

//////////////////
///////// calculation each biome position based on noise and radius
//////////////////
            for (BiomeData biome : data.GetBiomes()) {

                biomeIndex[0]++;
                int biomeXStart = (int) Math.round(biome.startPointX * (double) width);
                int biomeYStart = (int) Math.round(biome.startPointY * (double) height);
                int biomeWidth = (int) Math.round(biome.width * (double) width);
                int biomeHeight = (int) Math.round(biome.height * (double) height);

                int beginX = Math.max(biomeXStart - biomeWidth / 2, 0);
                int beginY = Math.max(biomeYStart - biomeHeight / 2, 0);
                int endX = Math.min(biomeXStart + biomeWidth / 2, width);
                int endY = Math.min(biomeYStart + biomeHeight / 2, height);
                if (biome.width == 1.0 && biome.height == 1.0) {
                    beginX = 0;
                    beginY = 0;
                    endX = width;
                    endY = height;
                }
                for (int x = beginX; x < endX; x++) {
                    for (int y = beginY; y < endY; y++) {
                        //value 0-1 based on noise
                        float noiseValue = ((float) noise.eval(x / (float) width * noiseZoom, y / (float) height * noiseZoom) + 1) / 2f;
                        noiseValue *= biome.noiseWeight;
                        //value 0-1 based on dist to origin
                        float distanceValue = ((float) Math.sqrt((x - biomeXStart) * (x - biomeXStart) + (y - biomeYStart) * (y - biomeYStart))) / (Math.max(biomeWidth, biomeHeight) / 2f);
                        distanceValue *= biome.distWeight;
                        if (noiseValue + distanceValue < 1.0 || biome.invertHeight && (1 - noiseValue) + distanceValue < 1.0) {
                            biomeMap[x][y] |= (1L << biomeIndex[0]);
                        }
                    }
                }
            }
            currentTime[0] = measureGenerationTime("biome claims", currentTime[0]);

//////////////////
///////// set poi placement
//////////////////
            List<PointOfInterest> towns = new ArrayList<>();
            List<PointOfInterest> notTowns = new ArrayList<>();
            List<Rectangle> otherPoints = new ArrayList<>();

            TextureAtlas mapMarker = Config.instance().getAtlas(Paths.MAP_MARKER);
            TextureData texture = mapMarker.getTextures().first().getTextureData();
            if (!texture.isPrepared())
                texture.prepare();
            Pixmap mapMarkerPixmap = texture.consumePixmap();
            clearTerrain((int) (data.width * data.playerStartPosX), (int) (data.height * data.playerStartPosY), 10);
            //otherPoints.add(new Rectangle(((float) data.width * data.playerStartPosX * (float) data.tileSize) - data.tileSize * 3, ((float) data.height * data.playerStartPosY * data.tileSize) - data.tileSize * 3, data.tileSize * 6, data.tileSize * 6));
            boolean running = true;
            // Rerun budget for the essential-POI no-silent-drop check below - array so the
            // count survives the labeled `continue here` restarts.
            final int[] essentialPlacementReruns = {0};
            // [TFR-PoiPlacement] diagnostic counters (2026-08-24 user request: "create a log entry
            // and see if/how many times we hit the 500-attempt failure. Or even how high that
            // goes" - ahead of a possible town-only exclusion-zone change, to have a real baseline
            // for how crowded placement already is). totalRegenRestarts persists across every
            // `continue here` full-pass restart (both the collision path and the essential-POI
            // path below); the other three reset at the top of each pass so the summary logged
            // after the loop reflects only the pass that actually finished.
            final int[] totalRegenRestarts = {0};
            final int[] maxAttemptsSeen = {0};
            final int[] highAttemptPlacements = {0};
            final int[] totalPlacements = {0};
            final int HIGH_ATTEMPT_THRESHOLD = 50;
            here:
            while (running) {
                mapPoiIds = new PointOfInterestMap(getChunkSize(), data.tileSize, data.width / getChunkSize(), data.height / getChunkSize());
                maxAttemptsSeen[0] = 0;
                highAttemptPlacements[0] = 0;
                totalPlacements[0] = 0;
                int biomeIndex2 = -1;
                running = false;
                for (BiomeData biome : data.GetBiomes()) {
                    biomeIndex2++;
                    // Essentials place FIRST while the map is still empty (2026-08-08, after a
                    // generated world came out missing White's Capital and the Emrakul castle):
                    // with pool rotation overprovisioning dungeons 5x, placement runs crowded, and
                    // a one-of POI whose 500 attempts all landed on occupied/wrong-biome spots was
                    // silently dropped (see the not-placed check after the attempt loop below).
                    // Order: castles/capitals/Spawn/story-quest POIs, then towns, then the rest.
                    List<PointOfInterestData> orderedPois = new ArrayList<>(biome.getPointsOfInterest());
                    orderedPois.sort(Comparator.comparingInt(World::poiPlacementPriority));
                    for (PointOfInterestData poi : orderedPois) {
                        // Dungeon rotation pool (MOD_SCOPE.md #15, user redesign): rotatable
                        // dungeons/caves are overprovisioned POOL_MULTIPLIER-fold at placement;
                        // DungeonRotation.initializeNewWorld() (called right after this loop)
                        // hides all but 1/POOL_MULTIPLIER of them, and rotation later swaps
                        // despawned ones for reserve locations - dungeons genuinely move around
                        // the map instead of returning in place. Non-rotatable POIs and planes
                        // without the flag place exactly as stock.
                        int placeCount = poi.count;
                        if (isDungeonRotationEnabled() && DungeonRotation.isRotatableData(poi))
                            placeCount *= DungeonRotation.POOL_MULTIPLIER;
                        for (int i = 0; i < placeCount; i++) {
                            boolean placedThisInstance = false;
                            for (int counter = 0; counter < 500; counter++)//tries 500 times to find a free point
                            {
                                float radius = (float) Math.sqrt(((random.nextDouble()) / 2 * poi.radiusFactor));
                                float theta = (float) (random.nextDouble() * 2 * Math.PI);
                                float x = (float) (radius * Math.cos(theta));
                                x *= (biome.width * width / 2);
                                x += (biome.startPointX * width);
                                float y = (float) (radius * Math.sin(theta));
                                y *= (biome.height * height / 2);
                                y += (height - (biome.startPointY * height));

                                y += (poi.offsetY * (biome.height * height));
                                x += (poi.offsetX * (biome.width * width));

                                if ((int) x < 0 || (int) y <= 0 || (int) y >= height || (int) x >= width || biomeIndex2 != highestBiome(getBiome((int) x, (int) y))) {
                                    continue;
                                }
                                // Center Towns (MOD_SCOPE #102, user spec 2026-09-03): no other town inside the star's disc
                                if (isOrdinaryTownData(poi) && Math.hypot(x - width / 2.0, y - height / 2.0) < starTownExclusionRadius()) {
                                    continue;
                                }
                                // Round 98: ordinary towns keep townMinSpacingTiles from every town already placed
                                if (isOrdinaryTownData(poi) && tooCloseToPlacedTown(towns, x, y)) {
                                    continue;
                                }

                                x *= data.tileSize;
                                y *= data.tileSize;

                                boolean breakNextLoop = false;
                                for (Rectangle rect : otherPoints) {
                                    if (rect.contains(x, y)) {
                                        breakNextLoop = true;
                                        break;
                                    }
                                }
                                if (breakNextLoop) {
                                    boolean foundSolution = false;
                                    boolean noSolution = false;
                                    breakNextLoop = false;
                                    for (int xi = -1; xi < 2 && !foundSolution; xi++) {
                                        for (int yi = -1; yi < 2 && !foundSolution; yi++) {
                                            for (Rectangle rect : otherPoints) {
                                                if (rect.contains(x + xi * data.tileSize, y + yi * data.tileSize)) {
                                                    noSolution = true;
                                                    break;
                                                }
                                            }
                                            if (!noSolution) {
                                                foundSolution = true;
                                                x = x + xi * data.tileSize;
                                                y = y + yi * data.tileSize;


                                            }
                                        }
                                    }
                                    if (!foundSolution) {
                                        if (counter == 499) {
                                            totalRegenRestarts[0]++;
                                            System.err.print("[TFR-PoiPlacement] Can not place POI " + poi.name
                                                    + "...Rerunning.. (full-pass restart #" + totalRegenRestarts[0] + ")\n");
                                            running = true;
                                            towns.clear();
                                            notTowns.clear();
                                            otherPoints.clear();
                                            clearTerrain((int) (data.width * data.playerStartPosX), (int) (data.height * data.playerStartPosY), 10);
                                            storedInfo.clear();
                                            // The discarded pass consumed town names it never kept -
                                            // without this reset, enough reruns drain the pool dry and
                                            // every later town silently falls back to its template's
                                            // generic name ("Waste Town Generic"). Root cause of the
                                            // 2026-08-08 duplicate-town-names report; reruns got
                                            // frequent once pool rotation raised placement density.
                                            for (BiomeData biomeToReset : data.GetBiomes())
                                                biomeToReset.resetTownNamePool();
                                            continue here;
                                        }
                                        continue;
                                    }
                                }
                                otherPoints.add(new Rectangle(x - data.tileSize * 4, y - data.tileSize * 4, data.tileSize * 8, data.tileSize * 8));
                                PointOfInterest newPoint = new PointOfInterest(poi, new Vector2(x, y), random);
                                clearTerrain((int) (x / data.tileSize), (int) (y / data.tileSize), 3);
                                mapPoiIds.add(newPoint);

                                TextureAtlas.AtlasRegion marker = mapMarker.findRegion(mapMarkerKey(poi));

                                if (marker != null) {
                                    int xInPixels = (int) ((x / data.tileSize) * data.miniMapTileSize);
                                    int yInPixels = (int) ((height - (y / data.tileSize)) * data.miniMapTileSize);
                                    xInPixels -= (marker.getRegionWidth() / 2);
                                    yInPixels -= (marker.getRegionHeight() / 2);
                                    drawPixmapLater(mapMarkerPixmap, marker.getRegionX(), marker.getRegionY(),
                                            marker.getRegionWidth(), marker.getRegionHeight(), xInPixels, yInPixels, marker.getRegionWidth(), marker.getRegionHeight());
                                }


                                if (poi.type != null && (poi.type.equals("town") || poi.type.equals("capital"))) {
                                    if (!newPoint.hasDisplayName()) {
                                        if (poi.displayName == null || poi.displayName.isEmpty()) {
                                            newPoint.setDisplayName(biome.getNewTownName());
                                        } else {
                                            newPoint.setDisplayName(poi.getDisplayName());
                                        }
                                    }
                                    towns.add(newPoint);
                                } else {
                                    notTowns.add(newPoint);
                                }
                                placedThisInstance = true;
                                // [TFR-PoiPlacement] - see the counter declarations above `here:`.
                                int attemptsUsed = counter + 1;
                                totalPlacements[0]++;
                                if (attemptsUsed > maxAttemptsSeen[0])
                                    maxAttemptsSeen[0] = attemptsUsed;
                                if (attemptsUsed > HIGH_ATTEMPT_THRESHOLD)
                                    highAttemptPlacements[0]++;
                                break;
                            }
                            // No-silent-drop check (2026-08-08): the attempt loop can also exhaust
                            // through the out-of-bounds/wrong-biome `continue` above, which never
                            // reaches the counter==499 rerun branch - a generated world shipped
                            // MISSING White's Capital and the Emrakul castle this way. An essential
                            // POI that failed all 500 attempts restarts placement like the
                            // collision path does (bounded by essentialPlacementReruns so a
                            // genuinely impossible layout can't hang world-gen forever); a
                            // non-essential drop is at least logged now instead of vanishing.
                            if (!placedThisInstance) {
                                if (isEssentialPoi(poi) && essentialPlacementReruns[0] < 10) {
                                    essentialPlacementReruns[0]++;
                                    totalRegenRestarts[0]++;
                                    System.err.print("[TFR-PoiPlacement] Essential POI " + poi.name + " could not be placed (attempt "
                                            + essentialPlacementReruns[0] + "/10)...Rerunning.. (full-pass restart #" + totalRegenRestarts[0] + ")\n");
                                    running = true;
                                    towns.clear();
                                    notTowns.clear();
                                    otherPoints.clear();
                                    clearTerrain((int) (data.width * data.playerStartPosX), (int) (data.height * data.playerStartPosY), 10);
                                    storedInfo.clear();
                                    for (BiomeData biomeToReset : data.GetBiomes())
                                        biomeToReset.resetTownNamePool();
                                    continue here;
                                }
                                System.err.print("[TFR-PoiPlacement] " + (isEssentialPoi(poi) ? "CRITICAL: essential " : "")
                                        + "POI " + poi.name + " instance " + (i + 1) + "/" + placeCount
                                        + " not placed after 500 attempts, skipping\n");
                            }
                        }
                    }
                }
            }
            // [TFR-PoiPlacement] summary for the pass that actually completed (see the counter
            // declarations above `here:`) - answers "how many times did we hit the 500-attempt
            // failure, and how high does it go" in one greppable line per world generation,
            // rather than needing to count individual failure/rerun lines by hand.
            System.out.println("[TFR-PoiPlacement] summary: " + totalPlacements[0] + " POI(s) placed, max attempts used="
                    + maxAttemptsSeen[0] + "/500, placements needing >" + HIGH_ATTEMPT_THRESHOLD + " attempts="
                    + highAttemptPlacements[0] + ", full-pass restarts=" + totalRegenRestarts[0]);
            currentTime[0] = measureGenerationTime("poi placement", currentTime[0]);

            // Hide the reserve 4/5 of the rotation pool BEFORE anything bakes markers or picks
            // quest targets - see the placement loop's POOL_MULTIPLIER comment above.
            recordStarTowns(); // Center Towns (MOD_SCOPE #102): positions are final once placement is done
            DungeonRotation.initializeNewWorld(this);

//////////////////
///////// assign terrain/structure content per tile (Territory Control: spatially-aware, #7)
//////////////////
            // Every color's real castle position is now known (POI placement above just finished),
            // so this pass never has to predict where a castle will land - see MOD_CHANGELOG.md for
            // why prediction was tried and rejected during planning (predicted vs. actual castle
            // position diverge by a real, non-negligible amount - a mismatched ring where content
            // and ownership disagree is a rendering bug, not just a style issue, since rendering
            // interprets terrainMap's raw index using whichever biome's BiomeTexture the tile's
            // biomeMap bit currently names, and BiomeTexture is frozen per-biome at
            // loadWorldData() time). This is the terrain/structure half of what generateNew()'s
            // earlier biome-claim loop used to do in one pass - split so this half could move here.
            BiomeData colorlessBiomeRef = null;
            for (BiomeData b : data.GetBiomes())
                if ("waste".equalsIgnoreCase(b.name)) { colorlessBiomeRef = b; break; }

            biomeIndex[0] = -1;
            for (BiomeData biome : data.GetBiomes()) {
                biomeIndex[0]++;
                int biomeXStart = (int) Math.round(biome.startPointX * (double) width);
                int biomeYStart = (int) Math.round(biome.startPointY * (double) height);
                int biomeWidth = (int) Math.round(biome.width * (double) width);
                int biomeHeight = (int) Math.round(biome.height * (double) height);

                int beginX = Math.max(biomeXStart - biomeWidth / 2, 0);
                int beginY = Math.max(biomeYStart - biomeHeight / 2, 0);
                int endX = Math.min(biomeXStart + biomeWidth / 2, width);
                int endY = Math.min(biomeYStart + biomeHeight / 2, height);
                if (biome.width == 1.0 && biome.height == 1.0) {
                    beginX = 0;
                    beginY = 0;
                    endX = width;
                    endY = height;
                }

                // Only set (non-null) for one of the 5 AI colors, with Territory Control enabled -
                // everyone else (base/ocean, colorless, player, or any AI color when the feature is
                // off) computes exactly as before, unconditionally using their own real content.
                BiomeStructureData[] redirectStructures = buildColorlessRedirectStructuresBlocking(biome.name);
                PointOfInterest realCastle = redirectStructures != null ? TerritoryControl.findCastle(this, biome.name) : null;
                int castleTileX = 0, castleTileY = 0;
                if (realCastle != null) {
                    castleTileX = (int) (realCastle.getPosition().x / data.tileSize);
                    castleTileY = (int) (realCastle.getPosition().y / data.tileSize);
                }
                int keepRadiusSq = TerritoryControl.CASTLE_KEEP_RADIUS_TILES * TerritoryControl.CASTLE_KEEP_RADIUS_TILES;
                int scannedTiles = 0, redirectedTiles = 0;

                for (int x = beginX; x < endX; x++) {
                    for (int y = beginY; y < endY; y++) {
                        if (highestBiome(biomeMap[x][y]) != biomeIndex[0])
                            continue; // some other, higher-priority biome also claimed this tile - its own pass handles it
                        scannedTiles++;

                        BiomeTerrainData[] terrainSource = biome.terrain;
                        BiomeStructureData[] structuresSource = biome.structures;
                        boolean usingRedirect = false;
                        if (realCastle != null) {
                            // x needs no flip (matches getBiome()'s own convention); y does - this
                            // loop's y is the same raw array space getBiome(wx,wy)=biomeMap[wx]
                            // [height-wy-1] reads from, so height-y-1 recovers the "world tile" y
                            // that realCastle.getPosition() (a world/pixel position) is already in,
                            // once divided by tileSize - same conversion neutralizeTerritoryOutsideRadius()
                            // and claimWastelandRing() already use, just in the opposite direction.
                            int dx = x - castleTileX;
                            int dy = (height - y - 1) - castleTileY;
                            if (dx * dx + dy * dy > keepRadiusSq) {
                                terrainSource = colorlessBiomeRef.terrain;
                                structuresSource = redirectStructures;
                                usingRedirect = true;
                                redirectedTiles++;
                            }
                        }

                        int terrainCounter = 1;
                        terrainMap[x][y] = 0;
                        if (terrainSource != null) {
                            for (BiomeTerrainData terrain : terrainSource) {
                                float terrainNoise = ((float) noise.eval(x / (float) width * (noiseZoom * terrain.resolution), y / (float) height * (noiseZoom * terrain.resolution)) + 1) / 2;
                                if (terrainNoise >= terrain.min && terrainNoise <= terrain.max) {
                                    terrainMap[x][y] = terrainCounter;
                                }
                                terrainCounter++;
                            }
                        }
                        if (biome.collision)
                            terrainMap[x][y] |= collisionBit;
                        if (structuresSource != null) {
                            for (BiomeStructureData structureData : structuresSource) {
                                BiomeStructure structure;
                                if (usingRedirect) {
                                    // Built synchronously by buildColorlessRedirectStructuresBlocking()
                                    // (already cached by the time Pass B reaches here in practice,
                                    // since it's called once per biome right above, before this
                                    // per-tile loop starts) - no async future to wait on, unlike a
                                    // biome's own real structures[] below.
                                    structure = getOrBuildNativePattern(structureData, biomeWidth, biomeHeight);
                                } else {
                                    while (!structureDataMap.containsKey(structureData)) {
                                        try {
                                            Thread.sleep(10);
                                        } catch (InterruptedException e) {
                                            throw new RuntimeException(e);
                                        }
                                    }
                                    structure = structureDataMap.get(structureData);
                                }
                                int structureXStart = x - (biomeXStart - biomeWidth / 2) - (int) ((structureData.x * biomeWidth) - (structureData.width * biomeWidth / 2));
                                int structureYStart = y - (biomeYStart - biomeHeight / 2) - (int) ((structureData.y * biomeHeight) - (structureData.height * biomeHeight / 2));

                                int structureIndex = structure.objectID(structureXStart, structureYStart);
                                if (structureIndex >= 0) {
                                    terrainMap[x][y] = terrainCounter + structureIndex;
                                    if (structure.collision(structureXStart, structureYStart))
                                        terrainMap[x][y] |= collisionBit;
                                    terrainMap[x][y] |= isStructureBit;
                                }

                                terrainCounter += structure.structureObjectCount();
                            }
                        }
                    }
                }

                if (redirectStructures != null)
                    System.out.println("[TerritoryControl] " + biome.name + ": placement - castle "
                            + (realCastle != null ? ("at (" + castleTileX + "," + castleTileY + ")") : "not found, real content used everywhere")
                            + ", " + (scannedTiles - redirectedTiles) + "/" + scannedTiles + " claimed tiles kept real content");
            }
            currentTime[0] = measureGenerationTime("territory control placement", currentTime[0]);

//////////////////
///////// sort towns and build roads in between
//////////////////
            List<Pair<PointOfInterest, PointOfInterest>> allSortedTowns = new ArrayList<>();

            // Round 99 (user spec 2026-09-03): back to the nearest-neighbor network (the round-98 per-color
            // trees drew 49 edges per color - far too many roads at the start), thinned by
            // TuningData.initialTownRoadSkipFraction. Roads then grow with the game: every capture, AI or
            // player, links the new town to the closest town already road-connected to that owner's
            // capital (TerritoryControl.connectCapturedTownByRoad). The star's explicit edges never skip.
            float roadSkip = Config.instance().getTuningData().initialTownRoadSkipFraction;
            int skippedRoadSources = 0;
            HashSet<Long> usedEdges = new HashSet<>();
            int[] roadDegree = new int[towns.size()]; // round 100: links per town; edges touching a Ring City or Spawn count for nobody
            boolean[] anyRoadLink = new boolean[towns.size()];
            int maxLinks = Config.instance().getTuningData().townMaxRoadLinks;
            if (maxLinks <= 0)
                maxLinks = 5;//edge is first 32 bits id of first id and last 32 bits id of second
            for (int i = 0; i < towns.size() - 1; i++) {

                PointOfInterest current = towns.get(i);
                if (roadLinkFull(towns, roadDegree, i, maxLinks))
                    continue; // round 100: this town already has its maximum links
                if (roadSkip > 0f && random.nextFloat() < roadSkip) {
                    skippedRoadSources++;
                    continue; // round 99: fewer world-gen roads
                }
                int smallestIndex = -1;
                int secondSmallestIndex = -1;
                float smallestDistance = Float.MAX_VALUE;
                for (int j = 0; j < towns.size(); j++) {

                    if (i == j || usedEdges.contains((long) i | ((long) j << 32)) || roadLinkFull(towns, roadDegree, j, maxLinks))
                        continue;
                    float dist = current.getPosition().dst(towns.get(j).getPosition());
                    if (dist > data.maxRoadDistance)
                        continue;
                    if (dist < smallestDistance) {
                        smallestDistance = dist;
                        secondSmallestIndex = smallestIndex;
                        smallestIndex = j;

                    }
                }
                if (smallestIndex < 0)
                    continue;
                usedEdges.add((long) i | ((long) smallestIndex << 32));
                usedEdges.add((long) i << 32 | ((long) smallestIndex));
                allSortedTowns.add(Pair.of(current, towns.get(smallestIndex)));
                countRoadLink(towns, roadDegree, anyRoadLink, i, smallestIndex);

                if (secondSmallestIndex < 0)
                    continue;
                usedEdges.add((long) i | ((long) secondSmallestIndex << 32));
                usedEdges.add((long) i << 32 | ((long) secondSmallestIndex));
                //allSortedTowns.add(Pair.of(current, towns.get(secondSmallestIndex)));
            }
            // Round 100 (user spec 2026-09-03): every town starts with at least one link. A town left
            // without one (skipped source and nobody's nearest) joins its nearest town that still has
            // room - any distance. Ring Cities and Spawn are exempt (the star's own roads cover them).
            int rescuedTowns = 0;
            for (int i = 0; i < towns.size(); i++) {
                if (anyRoadLink[i] || isRingOrSpawnTown(towns.get(i)))
                    continue;
                int nearest = -1;
                float nearestDist = Float.MAX_VALUE;
                for (int j = 0; j < towns.size(); j++) {
                    if (i == j || roadLinkFull(towns, roadDegree, j, maxLinks))
                        continue;
                    float dist = towns.get(i).getPosition().dst(towns.get(j).getPosition());
                    if (dist < nearestDist) {
                        nearestDist = dist;
                        nearest = j;
                    }
                }
                if (nearest < 0)
                    continue;
                usedEdges.add((long) i | ((long) nearest << 32));
                usedEdges.add((long) i << 32 | ((long) nearest));
                allSortedTowns.add(Pair.of(towns.get(i), towns.get(nearest)));
                countRoadLink(towns, roadDegree, anyRoadLink, i, nearest);
                rescuedTowns++;
            }
            // Center Towns (MOD_SCOPE #102): a road from the campfire straight to each star town, drawn
            // by the same pass as every other town road - the star's spokes.
            PointOfInterest campfire = null;
            List<PointOfInterest> starTowns = new ArrayList<>();
            for (PointOfInterest t : towns) {
                if (t.getData() == null || t.getData().name == null)
                    continue;
                if ("Spawn".equals(t.getData().name))
                    campfire = t;
                else if (t.getData().name.startsWith("Waste Town Center"))
                    starTowns.add(t);
            }
            if (campfire != null)
                for (PointOfInterest st : starTowns)
                    allSortedTowns.add(Pair.of(campfire, st));
            // ... and the star's rim: every Center Town joined to every other (user spec 2026-09-03),
            // ten edges for five towns - explicit pairs bypass maxRoadDistance like the spokes do.
            for (int a = 0; a < starTowns.size(); a++)
                for (int b = a + 1; b < starTowns.size(); b++)
                    allSortedTowns.add(Pair.of(starTowns.get(a), starTowns.get(b)));
            System.out.println("[TFR-Roads] world-gen town roads: " + allSortedTowns.size() + " edge(s) including the star's, "
                    + skippedRoadSources + " nearest-neighbor source(s) skipped (fraction " + roadSkip + "), "
                    + rescuedTowns + " unlinked town(s) rescued, max " + maxLinks + " links per town");
            List<Pair<PointOfInterest, PointOfInterest>> allPOIPathsToNextTown = new ArrayList<>();
            for (int i = 0; i < notTowns.size() - 1; i++) {

                PointOfInterest poi = notTowns.get(i);
                int smallestIndex = -1;
                float smallestDistance = Float.MAX_VALUE;
                for (int j = 0; j < towns.size(); j++) {

                    float dist = poi.getPosition().dst(towns.get(j).getPosition());
                    if (dist < smallestDistance) {
                        smallestDistance = dist;
                        smallestIndex = j;

                    }
                }
                if (smallestIndex < 0)
                    continue;
                allPOIPathsToNextTown.add(Pair.of(poi, towns.get(smallestIndex)));
            }
            biomeIndex[0]++;

            //reset terrain path to the next town
            for (Pair<PointOfInterest, PointOfInterest> poiToTown : allPOIPathsToNextTown) {
                futures.add(CompletableFuture.supplyAsync(()-> {
                    int startX = (int) poiToTown.getKey().getTilePosition(data.tileSize).x;
                    int startY = (int) poiToTown.getKey().getTilePosition(data.tileSize).y;
                    int x1 = (int) poiToTown.getValue().getTilePosition(data.tileSize).x;
                    int y1 = (int) poiToTown.getValue().getTilePosition(data.tileSize).y;
                    int dx = Math.abs(x1 - startX);
                    int dy = Math.abs(y1 - startY);
                    int sx = startX < x1 ? 1 : -1;
                    int sy = startY < y1 ? 1 : -1;
                    int err = dx - dy;
                    int e2;
                    for (int i = 0; i < 1000; i++) {
                        if (startX < 0 || startY <= 0 || startX >= width || startY > height) continue;
                        if ((terrainMap[startX][height - startY] & collisionBit) != 0)//clear terrain if it has collision
                            terrainMap[startX][height - startY] = 0;

                        if (startX == x1 && startY == y1)
                            break;
                        e2 = 2 * err;
                        if (e2 > -dy) {
                            err = err - dy;
                            startX = startX + sx;
                        } else if (e2 < dx) {
                            err = err + dx;
                            startY = startY + sy;
                        }
                    }
                    return 0L;
                }).exceptionally(ex -> {
                    ex.printStackTrace();
                    return 0L;
                }));
            }
            futuresArray = futures.toArray(new CompletableFuture<?>[0]);
            CompletableFuture.allOf(futuresArray).join();
            futures.clear();
            for (Pair<PointOfInterest, PointOfInterest> townPair : allSortedTowns) {
                futures.add(CompletableFuture.supplyAsync(()-> {
                    int startX = (int) townPair.getKey().getTilePosition(data.tileSize).x;
                    int startY = (int) townPair.getKey().getTilePosition(data.tileSize).y;
                    int x1 = (int) townPair.getValue().getTilePosition(data.tileSize).x;
                    int y1 = (int) townPair.getValue().getTilePosition(data.tileSize).y;
                    for (int x = startX - 1; x < startX + 2; x++) {
                        for (int y = startY - 1; y < startY + 2; y++) {
                            if (x < 0 || y < 0 || x >= width || y >= height) continue;
                            biomeMap[x][height - y - 1] |= (1L << biomeIndex[0]);
                            terrainMap[x][height - y - 1] = 0;
                        }
                    }
                    int dx = Math.abs(x1 - startX);
                    int dy = Math.abs(y1 - startY);
                    int sx = startX < x1 ? 1 : -1;
                    int sy = startY < y1 ? 1 : -1;
                    int err = dx - dy;
                    int e2;
                    for (int i = 0; i < 1000; i++) {
                        if (startX < 0 || startY <= 0 || startX >= width || startY > height) continue;
                        biomeMap[startX][height - startY] |= (1L << biomeIndex[0]);
                        terrainMap[startX][height - startY] = 0;

                        if (startX == x1 && startY == y1)
                            break;
                        e2 = 2 * err;
                        if (e2 > -dy) {
                            err = err - dy;
                            startX = startX + sx;
                        } else if (e2 < dx) {
                            err = err + dx;
                            startY = startY + sy;
                        }
                    }
                    return 0L;
                }).exceptionally(ex -> {
                    ex.printStackTrace();
                    return 0L;
                }));
            }
            futuresArray = futures.toArray(new CompletableFuture<?>[0]);
            CompletableFuture.allOf(futuresArray).join();
            futures.clear();
            currentTime[0] = measureGenerationTime("roads", currentTime[0]);

//////////////////
///////// draw mini map
//////////////////

            Pixmap pix = new Pixmap(width * data.miniMapTileSize, height * data.miniMapTileSize, Pixmap.Format.RGBA8888);
            pix.setColor(1, 0, 0, 1);
            pix.fill();
            for (int x = 0; x < width; x++) {
                for (int y = 0; y < height; y++) {
                    if (highestBiome(biomeMap[x][y]) >= data.GetBiomes().size()) {
                        Pixmap smallPixmap = createSmallPixmap(data.roadTileset.tilesetAtlas, data.roadTileset.tilesetName, 0);
                        pix.drawPixmap(smallPixmap, x * data.miniMapTileSize, y * data.miniMapTileSize);
                    } else {

                        BiomeData biome = data.GetBiomes().get(highestBiome(biomeMap[x][y]));
                        int terrainIndex = terrainMap[x][y] & ~terrainMask;
                        if (terrainIndex > biome.terrain.length) {
                            Pixmap smallPixmap = createSmallPixmap(biome.tilesetAtlas, biome.tilesetName, 0);
                            pix.drawPixmap(smallPixmap, x * data.miniMapTileSize, y * data.miniMapTileSize);

                            terrainIndex -= biome.terrain.length;
                            terrainIndex--;
                            for (BiomeStructureData structData : biome.structures) {
                                if (terrainIndex >= structData.mappingInfo.length) {
                                    terrainIndex -= structData.mappingInfo.length;
                                    continue;
                                }
                                smallPixmap = createSmallPixmap(structData.structureAtlasPath, structData.mappingInfo[terrainIndex].name, 0);
                                pix.drawPixmap(smallPixmap, x * data.miniMapTileSize, y * data.miniMapTileSize);
                                break;
                            }
                        } else {
                            Pixmap smallPixmap = createSmallPixmap(biome.tilesetAtlas, biome.tilesetName, terrainIndex);
                            pix.drawPixmap(smallPixmap, x * data.miniMapTileSize, y * data.miniMapTileSize);
                        }

                    }

                }

            }
            for (Map.Entry<String, Pair<Pixmap, HashMap<String, Pixmap>>> entry : pixmapHash.entrySet()) {
                try {
                    entry.getValue().getLeft().dispose();
                } catch (Exception e) {
                    //e.printStackTrace();
                }
                for (Map.Entry<String, Pixmap> pairEntry : entry.getValue().getRight().entrySet()) {
                    try {
                        pairEntry.getValue().dispose();
                    } catch (Exception e) {
                        //e.printStackTrace();
                    }
                }
            }
            pixmapHash.clear();
            try {
                drawPixmapNow(pix);
            } catch (Exception e) {
                //e.printStackTrace();
            }
            currentTime[0] = measureGenerationTime("mini map", currentTime[0]);


//////////////////
///////// distribute small rocks and trees across the map
//////////////////
            mapObjectIds = new SpritesDataMap(getChunkSize(), data.tileSize, data.width / getChunkSize());
            for (int x = 0; x < width; x++) {
                for (int y = 0; y < height; y++) {
                    int invertedHeight = height - y - 1;
                    int currentBiome = highestBiome(biomeMap[x][invertedHeight]);
                    if (currentBiome >= data.GetBiomes().size())
                        continue;//roads
                    if (isStructure(x, y))
                        continue;
                    BiomeData biome = data.GetBiomes().get(currentBiome);
                    for (String name : biome.spriteNames) {
                        BiomeSpriteData sprite = data.GetBiomeSprites().getSpriteData(name);
                        double spriteNoise = (noise.eval(x / (double) width * noiseZoom * sprite.resolution, y / (double) invertedHeight * noiseZoom * sprite.resolution) + 1) / 2;
                        if (spriteNoise >= sprite.startArea && spriteNoise <= sprite.endArea) {
                            if (random.nextFloat() <= sprite.density) {
                                String spriteKey = sprite.key();
                                int key;
                                if (!mapObjectIds.containsKey(spriteKey)) {

                                    key = mapObjectIds.put(sprite.key(), sprite, data.GetBiomeSprites());
                                } else {
                                    key = mapObjectIds.intKey(spriteKey);
                                }
                                mapObjectIds.putPosition(key, new Vector2((((float) x) + .25f + random.nextFloat() / 2) * data.tileSize, (((float) y + .25f) - random.nextFloat() / 2) * data.tileSize));
                                break;//only on sprite per point
                            }
                        }
                    }
                }
            }
            mapMarkerPixmap.dispose();
            biomeImage = pix;
            rebuildFogOfWarPixmap();
            measureGenerationTime("sprites", currentTime[0]);
            // Territory Control (MOD_SCOPE.md #7), opt-in via territoryControlEnabled - runs after
            // everything else above has finished with every color's normal, full-size territory,
            // then sweeps each color down to a small area around its own castle. See
            // TerritoryControl.neutralizeAfterGeneration()'s own doc comment for why this replaced
            // shrinking each color's world-gen territory directly.
            if (isTerritoryControlEnabled()) {
                TerritoryControl.neutralizeAfterGeneration(this);
                // Functioning Neutral Towns (2026-08-24 user spec) - runs right after the
                // territory-control sweep above so the pool of neutral ("Waste Town") POIs is
                // final, including any AI-color towns just converted back to neutral by that
                // sweep. See TownRestoration.seedFunctioningNeutralTowns()'s own doc comment for
                // why this is a separate flag from TOWN_RESTORED_FLAG (that flag also means
                // "player-owned" everywhere TerritoryControl checks ownership).
                // MUST run before the minimap bake below (2026-08-25 bug fix - user report: "The
                // Neutral towns still have ruin icons on the Mini-map"): redrawAllPoiMarkers()
                // rasterizes each town's ruined/restored icon into biomeImage ONCE here at
                // world-gen and never again on its own, reading TownRestoration.isNeutralSeeded()
                // to decide. Seeding used to run AFTER this bake, so isNeutralSeeded() saw no
                // flag yet for any town and every one of the 20 seeded-functioning towns got
                // permanently baked in with ruin art - correct on the main map (which re-checks
                // live every frame) but wrong on the minimap forever after.
                if (isFunctioningNeutralTownsEnabled())
                    TownRestoration.seedFunctioningNeutralTowns(this);
                // neutralizeTerritoryOutsideRadius() (called above) already repaints the minimap
                // pixel for every tile it individually reassigns, which should already be complete
                // - but a full re-bake from biomeMap/terrainMap's now-final state is a stronger
                // guarantee than trusting every incremental repaint path to have covered everything
                // correctly, and it's cheap (the original bake measured ~0.1s for a full map).
                // Requested directly ("is there a way to re-initial it after everything is done")
                // after a report that the minimap didn't look right post-sweep.
                rebakeMinimapAfterTerritoryControl();
                // The sweep above repaints biomeImage directly, which can partially paint over a
                // nearby POI's marker icon - markers were only ever baked in once, by the ordinary
                // placement loop above, before this sweep existed to run afterward. Redraw them all
                // on top so none end up clipped (reported as "town icons look cut" on the minimap).
                // Must run after the re-bake above, not before - a bake only draws ground, so it
                // would otherwise erase these markers right back out again.
                redrawAllPoiMarkers();
            } else if (isFunctioningNeutralTownsEnabled()) {
                // Territory Control off: no minimap bake happens above for this to race against,
                // so the original standalone call is still correct here.
                TownRestoration.seedFunctioningNeutralTowns(this);
            }
            // Progressive Set Unlocks (MOD_SCOPE.md #4): one-time per new game, splits every real
            // edition into 6 shards (5 colors + neutral) - see EditionProgression's own doc
            // comment for why this runs here (world's own seeded Random, reproducible from seed).
            if (isEditionProgressionEnabled())
                EditionProgression.seedColorShards(this);
            System.out.println("Generating world took :\t\t" + ((System.currentTimeMillis() - startTime) / 1000f) + " s");
            WorldStage.getInstance().clearCache();

            if (GuiBase.isMobile())
                GuiBase.getInterface().preventSystemSleep(false);
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
        return true;
    }

    // Temple-icon minimap bug (2026-08-13) - "Story"-tagged unique landmark POIs (Tarnation,
    // Wizard Palace, Squirrel Farm, Gitrog Bog, Church of Valgavoth, Kenrith's Court, Eldrazi
    // Prison) all use type="castle" for its real gameplay side effects (wider vision radius,
    // castle music track - see TownRestoration/ColorReputation), but map_marker.atlas's "castle"
    // region is a small grey chapel-shaped icon that reads as a temple, so every one of these
    // genuinely-different locations baked the identical marker onto the minimap. Two prior
    // rounds (2026-08-11) only patched each POI's overworld sprite and never this lookup. Per
    // user decision, Story POIs key off the existing "dungeon" marker instead (no new art) while
    // `type` itself stays "castle" so the vision-radius/music behavior is untouched.
    //
    // Narrowed (adversarial review, 2026-08-13) - an earlier version of this fix keyed off the
    // "Story" tag alone, which also carries "Story" and is type="castle"/"cave"/"town" for OTHER
    // reasons: the player's own starting town "Spawn" (type="town"), 9 cave-type Story POIs
    // (Omenport, Three Tree City, the 5 Classroom POIs, etc.), and the 5 Chapter-1-Boss castles
    // (Black/Blue/Green/Red/White Castle, additionally tagged "Boss"/"Chapter1Boss") - none of
    // those were the reported bug (they either weren't castle-typed at all, or are meaningful
    // bosses that should keep their larger, more prominent 32x32 castle icon). Only remap when
    // the POI is BOTH type="castle" AND not one of those boss dungeons.
    private static String mapMarkerKey(PointOfInterestData data) {
        if (data == null)
            return null;
        // Legendary endgame POIs (2026-08-21, v1.00 feedback "Tier 1" - generalized same day
        // from the original Eldrazi Prison name-check, 4th user report on its icon): everything
        // tagged "Legendary" (the 8 Realm of Legends-ported dungeons + Eldrazi Prison) gets the
        // red triple-skull minimap glyph. The minimap and world map use entirely separate art
        // (marker atlas vs POI sprite), which is why sprite changes alone never fixed this.
        if (data.questTags != null) {
            for (String tag : data.questTags) {
                if ("Legendary".equals(tag))
                    return "sidebosshard";
            }
        }
        if ("castle".equals(data.type) && data.questTags != null) {
            boolean isStory = false;
            boolean isBoss = false;
            for (String tag : data.questTags) {
                if ("Story".equals(tag))
                    isStory = true;
                else if ("Boss".equals(tag))
                    isBoss = true;
            }
            if (isStory && !isBoss)
                return "dungeon";
        }
        return data.type;
    }

    // Territory Control (MOD_SCOPE.md #7) only - see generateNew()'s call site. Mirrors the
    // marker-drawing block inside the normal POI-placement loop above (same region lookup/offset
    // math), but draws directly onto biomeImage instead of queuing through drawPixmapLater() -
    // that queue was already flushed and cleared earlier in generateNew(), so it can't be reused
    // here, and a second, immediate draw is simpler anyway for a one-time post-sweep touch-up.
    private void redrawAllPoiMarkers() {
        redrawPoiMarkers(Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE);
    }

    // Per-call pixmap cache (2026-08-26 perf fix, the day-end freeze's biggest single cost):
    // consumePixmap() on a FileTextureData re-decodes the ENTIRE backing atlas PNG from disk
    // every call - and the 2026-08-25 town-icon generalization below made this method call it
    // once PER TOWN (hundreds of towns), and claimWastelandRing() called this method once per
    // owner per day (6x). Hundreds of full PNG decodes x6, synchronously on the render thread,
    // every single day-rollover. Each distinct atlas texture is now decoded at most ONCE per
    // call, shared by every marker drawn from it, and disposed together at the end.
    private static Pixmap markerPixmapFor(Map<Texture, Pixmap> cache, Texture texture) {
        Pixmap cached = cache.get(texture);
        if (cached != null)
            return cached;
        TextureData textureData = texture.getTextureData();
        if (!textureData.isPrepared())
            textureData.prepare();
        Pixmap fresh = textureData.consumePixmap();
        cache.put(texture, fresh);
        return fresh;
    }

    /** Rect-scoped marker redraw (2026-08-26 perf fix, companion to the pixmap cache above):
     *  claimWastelandRing() only needs to restore markers its own minimap tile repaints could
     *  have clipped - i.e. towns inside that day's claimed ring band - not all 2000+ POIs on the
     *  map. Bounds are inclusive WORLD-TILE coords (same unflipped wy space the claim loop
     *  tracks); pass Integer.MIN/MAX halves for the full-map redraw every other caller wants. */
    private void redrawPoiMarkers(int minTileX, int minTileY, int maxTileX, int maxTileY) {
        TextureAtlas mapMarker = Config.instance().getAtlas(Paths.MAP_MARKER);
        Map<Texture, Pixmap> pixmapCache = new HashMap<>();
        Pixmap mapMarkerPixmap = markerPixmapFor(pixmapCache, mapMarker.getTextures().first());
        int mm = data.miniMapTileSize;
        // Grainy town/Capitol icons fixed (2026-08-25 user report): every scaled drawPixmap below
        // (Capitol 64->32, town 48->16/23) was downscaling with Pixmap's default nearest-neighbor
        // sampling - the .atlas "filter: Nearest,Nearest" line only governs GL texture sampling,
        // it has no effect on this CPU-side Pixmap blit. BiLinear smooths the downscale instead;
        // restored to NearestNeighbour (libGDX's own default, and what every other biomeImage
        // draw elsewhere - crisp pixel-art terrain tiles - implicitly relies on) at the end of
        // this method so nothing else drawing onto this same Pixmap is affected.
        biomeImage.setFilter(Pixmap.Filter.BiLinear);
        for (PointOfInterest poi : getAllPointOfInterest()) {
            // Despawned/hidden POIs (dungeon rotation, quest-flag gates) get no minimap marker -
            // without this, a vanished dungeon kept its baked icon until the next full rebake.
            if (!poi.getActive())
                continue;
            int poiTileX = (int) (poi.getPosition().x / data.tileSize);
            int poiTileY = (int) (poi.getPosition().y / data.tileSize);
            if (poiTileX < minTileX || poiTileX > maxTileX || poiTileY < minTileY || poiTileY > maxTileY)
                continue; // outside the caller's dirty rect - marker untouched, nothing to restore
            // Player Capitol (user request 2026-08-13): its minimap marker is a scaled-down copy
            // of its OWN 64x64 overworld sprite ("Orazca", player_capitol.atlas) instead of the
            // generic 32x32 "capital" glyph - drawn at 32x32 so it exactly covers the old baked
            // glyph's footprint on existing saves (biomeImage is persisted with markers baked in;
            // a smaller icon would leave stale edge pixels). The Capitol only ever exists via
            // transformInto() at upgrade time (count 0 in points_of_interest.json), and every
            // runtime redraw path funnels through this method, so no world-gen-side change is
            // needed.
            if (TownRestoration.CAPITOL_POI_NAME.equals(poi.getData().name) && poi.getSprite() != null) {
                com.badlogic.gdx.graphics.g2d.TextureRegion capSprite = poi.getSprite();
                Pixmap capPixmap = markerPixmapFor(pixmapCache, capSprite.getTexture());
                int dstSize = 32;
                int cx = (int) ((poi.getPosition().x / data.tileSize) * mm) - dstSize / 2;
                int cy = (int) ((height - (poi.getPosition().y / data.tileSize)) * mm) - dstSize / 2;
                biomeImage.drawPixmap(capPixmap, capSprite.getRegionX(), capSprite.getRegionY(),
                        capSprite.getRegionWidth(), capSprite.getRegionHeight(), cx, cy, dstSize, dstSize);
                refreshFogForMarkerRect(cx, cy, dstSize, dstSize);
                continue;
            }
            // Starting Portal / "Spawn" (2026-08-25 user report: on the minimap this reads as an
            // ordinary town - it's type="town" so mapMarkerKey() falls through to the same
            // generic "town" hut glyph every other town uses). Reuses its own already-distinct
            // overworld campfire sprite (buildings.atlas "Spawn" region, 16x16 - the same native
            // size as the generic marker, so no scaling is needed) instead of the shared marker
            // atlas, same idea as the Capitol special case above.
            if (poi.getData().name != null && poi.getData().name.contains(" Town Center") && poi.getSprite() != null) {
                // Center Towns (MOD_SCOPE #102): their own castle art on the minimap, at the capital glyph's 32x32
                com.badlogic.gdx.graphics.g2d.TextureRegion starSprite = poi.getSprite();
                Pixmap starPixmap = markerPixmapFor(pixmapCache, starSprite.getTexture());
                int starSize = 32;
                int sx = (int) ((poi.getPosition().x / data.tileSize) * mm) - starSize / 2;
                int sy = (int) ((height - (poi.getPosition().y / data.tileSize)) * mm) - starSize / 2;
                biomeImage.drawPixmap(starPixmap, starSprite.getRegionX(), starSprite.getRegionY(),
                        starSprite.getRegionWidth(), starSprite.getRegionHeight(), sx, sy, starSize, starSize);
                refreshFogForMarkerRect(sx, sy, starSize, starSize);
                continue;
            }
            if ("Spawn".equals(poi.getData().name) && poi.getSprite() != null) {
                com.badlogic.gdx.graphics.g2d.TextureRegion spawnSprite = poi.getSprite();
                Pixmap spawnPixmap = markerPixmapFor(pixmapCache, spawnSprite.getTexture());
                int sx = (int) ((poi.getPosition().x / data.tileSize) * mm) - spawnSprite.getRegionWidth() / 2;
                int sy = (int) ((height - (poi.getPosition().y / data.tileSize)) * mm) - spawnSprite.getRegionHeight() / 2;
                biomeImage.drawPixmap(spawnPixmap, spawnSprite.getRegionX(), spawnSprite.getRegionY(),
                        spawnSprite.getRegionWidth(), spawnSprite.getRegionHeight(), sx, sy,
                        spawnSprite.getRegionWidth(), spawnSprite.getRegionHeight());
                refreshFogForMarkerRect(sx, sy, spawnSprite.getRegionWidth(), spawnSprite.getRegionHeight());
                continue;
            }
            // Generalized town icon (2026-08-25 user spec: "use the broken/ruin icons for ruined
            // cities and the normal fixed icons for existing neutral towns that are fine. Same
            // for the 5 AI colors. Create mini-map icons for their color towns.") - every
            // town-type POI already resolves its own correct overworld sprite via the same
            // broken/player-town/default priority PointOfInterestMapSprite.draw() uses: ruined ->
            // one of 16 broken-art variants, player-restored -> the dedicated PlayerTown art,
            // otherwise each color's own existing ForestTown/IslandTown/MountainTown/PlainsTown/
            // SwampTown/WasteTown sprite. Drawing THAT instead of the shared generic "town" hut
            // glyph needs no new art for the 5 AI colors at all - just reusing what the main map
            // already shows. Player Capitol/Spawn keep their own dedicated special cases above
            // (distinct POI names, not handled via type here).
            // Pre-seeded functioning neutral towns use the original base-game "town" hut glyph
            // (2026-08-26 user request with screenshot: "use the Original Town icons, from base
            // game for the Neutral towns that are restored from the start" - the downscaled
            // WasteTown building art kept reading as "ruined" on the minimap no matter how it
            // was filtered/sized). Drawn at 20x20, not the glyph's native 16x16, so it exactly
            // covers the 20x20 WasteTown footprint the previous build baked into existing saves'
            // persisted biomeImage (same stale-edge-pixels reasoning as the Capitol's own 32x32
            // comment above).
            boolean neutralSeeded = "town".equals(poi.getData().type)
                    && TownRestoration.isNeutralSeededTown(WorldSave.getCurrentSave().peekPointOfInterestChanges(poi.getID()));
            if (neutralSeeded) {
                TextureAtlas.AtlasRegion hutGlyph = mapMarker.findRegion("town");
                if (hutGlyph != null) {
                    int dstSize = 20;
                    int hx = (int) ((poi.getPosition().x / data.tileSize) * mm) - dstSize / 2;
                    int hy = (int) ((height - (poi.getPosition().y / data.tileSize)) * mm) - dstSize / 2;
                    biomeImage.drawPixmap(mapMarkerPixmap, hutGlyph.getRegionX(), hutGlyph.getRegionY(),
                            hutGlyph.getRegionWidth(), hutGlyph.getRegionHeight(), hx, hy, dstSize, dstSize);
                    refreshFogForMarkerRect(hx, hy, dstSize, dstSize);
                    continue;
                }
                // glyph missing from the atlas for some reason - fall through to mapMarkerKey below
            }
            if (!neutralSeeded && "town".equals(poi.getData().type) && poi.getSprite() != null) {
                com.badlogic.gdx.graphics.g2d.TextureRegion brokenTexture = TownRestoration.getBrokenTownSprite(poi);
                com.badlogic.gdx.graphics.g2d.TextureRegion townTexture = brokenTexture;
                if (townTexture == null)
                    townTexture = TownRestoration.getPlayerTownSprite(poi);
                if (townTexture == null)
                    townTexture = poi.getSprite();
                Pixmap townPixmap = markerPixmapFor(pixmapCache, townTexture.getTexture());
                // Base footprint 16->20 (2026-08-25 user request: "maybe slightly bigger" to help
                // with graininess, alongside the BiLinear downscale set above) - not the town
                // sprite's own much-larger native size, so restored/AI-color towns still don't
                // dwarf every other minimap icon; ruined keeps the existing ~15% bump on top
                // (2026-08-15 user request: "they look small next to the fixed/repaired towns").
                int dstSize = brokenTexture != null ? Math.round(20 * 1.15f) : 20;
                int tx = (int) ((poi.getPosition().x / data.tileSize) * mm) - dstSize / 2;
                int ty = (int) ((height - (poi.getPosition().y / data.tileSize)) * mm) - dstSize / 2;
                biomeImage.drawPixmap(townPixmap, townTexture.getRegionX(), townTexture.getRegionY(),
                        townTexture.getRegionWidth(), townTexture.getRegionHeight(), tx, ty, dstSize, dstSize);
                refreshFogForMarkerRect(tx, ty, dstSize, dstSize);
                continue;
            }
            TextureAtlas.AtlasRegion marker = mapMarker.findRegion(mapMarkerKey(poi.getData()));
            if (marker == null)
                continue;
            // This fallback draws non-town markers (dungeons, castles, etc.) at native size, plus
            // the pre-seeded functioning neutral towns routed here deliberately (see above).
            int xInPixels = (int) ((poi.getPosition().x / data.tileSize) * mm);
            int yInPixels = (int) ((height - (poi.getPosition().y / data.tileSize)) * mm);
            xInPixels -= marker.getRegionWidth() / 2;
            yInPixels -= marker.getRegionHeight() / 2;
            biomeImage.drawPixmap(mapMarkerPixmap, marker.getRegionX(), marker.getRegionY(),
                    marker.getRegionWidth(), marker.getRegionHeight(), xInPixels, yInPixels,
                    marker.getRegionWidth(), marker.getRegionHeight());
            refreshFogForMarkerRect(xInPixels, yInPixels, marker.getRegionWidth(), marker.getRegionHeight());
        }
        for (Pixmap cached : pixmapCache.values())
            cached.dispose();
        // Restore the default filter - every OTHER biomeImage draw elsewhere (crisp pixel-art
        // terrain tiles) implicitly relies on NearestNeighbour and never sets it explicitly.
        biomeImage.setFilter(Pixmap.Filter.NearestNeighbour);
    }

    /** Fog-of-war companion to the marker draws above (2026-08-15 bug fix: "town icon missing" /
     *  "icons cut off by terrain growth") - redrawAllPoiMarkers() only ever painted markers onto
     *  biomeImage, never onto fogOfWarPixmap, which World.getBiomeImage() actually returns (and
     *  every on-screen minimap reads from) whenever fog of war is enabled - the exact same
     *  "fog overlay holds tile COPIES, not a live view of biomeImage" mechanism already root-
     *  caused and fixed once for refreshWorldMapMarkers() (see that method's own
     *  rebuildFogOfWarPixmap() call), but never carried over to this method's own two call sites
     *  (repaintBiomeAroundTown() at town capture, claimWastelandRing() on daily growth) - a player's
     *  own town marker could go missing (fog copied the marker-less tile moments before this method
     *  finally drew it) or a nearby town's marker could look "cut off" (only the tiles inside that
     *  day's newly-claimed ring got re-copied into the fog pixmap, stranding the rest of a marker
     *  that spans multiple tiles). Converts the just-drawn marker's PIXEL rect back to tile indices
     *  and re-syncs just those cells - cheap (a handful of tiles per marker) and safe unconditionally:
     *  updateFogOfWarPixmap() itself no-ops without a live fogOfWarPixmap/biomeImage, and correctly
     *  re-paints solid black rather than the marker for any tile not yet in explored[][]. */
    private void refreshFogForMarkerRect(int pixelX, int pixelY, int pixelW, int pixelH) {
        if (fogOfWarPixmap == null || data == null)
            return;
        int mm = data.miniMapTileSize;
        int tx0 = Math.max(0, pixelX / mm);
        int ty0 = Math.max(0, pixelY / mm);
        int tx1 = Math.min(width - 1, (pixelX + pixelW - 1) / mm);
        int ty1 = Math.min(height - 1, (pixelY + pixelH - 1) / mm);
        for (int tx = tx0; tx <= tx1; tx++)
            for (int ty = ty0; ty <= ty1; ty++)
                updateFogOfWarPixmap(tx, ty);
    }

    // Territory Control (MOD_SCOPE.md #7) only - see generateNew()'s call site, right after
    // neutralizeAfterGeneration(). Re-derives the minimap Pixmap from biomeMap/terrainMap's current
    // (now-final) state, the same computation the original bake earlier in generateNew() already
    // did - kept as a separate method rather than refactoring that original call site to share this
    // one, specifically to avoid disturbing its proven sequencing: biomeImage there is deliberately
    // assigned *after* the doodad-placement pass, not immediately after baking, and
    // rebuildFogOfWarPixmap() (called right after) reads biomeImage's dimensions directly - a subtle
    // ordering dependency not worth risking a second time this session for what's otherwise a
    // straightforward, self-contained re-bake. Explicitly sets biomeImage itself (unlike the
    // original inline bake, which relies on that later, separate assignment) - needed here since
    // there's no such later assignment to fall back on for this call.
    // Full minimap refresh for runtime POI hide/show (dungeon rotation): a POI's marker is BAKED
    // pixels, so hiding one requires repainting the ground over its icon - the ground rebake
    // below does that wholesale, then the marker pass re-draws only still-active POIs (see the
    // getActive() filter in redrawAllPoiMarkers()). Rare-event cost (a handful of despawns/
    // respawns per in-game day at most), and the bake itself measures ~0.1s.
    /**
     * Places a brand-new POI at a free walkable tile within [minTiles, maxTiles] of centerPos
     * (world/pixel coordinates), registers it, and repaints the minimap. Returns the new POI or
     * null when no free spot was found. Built for TerritoryControl.ensureCapital()'s fallback
     * (2026-08-08: a world generated with NO town inside White's keep radius, so promotion had
     * nothing to promote and the color simply had no capital) - but written generically.
     */
    public PointOfInterest addPointOfInterestNear(PointOfInterestData poiData, Vector2 centerPos, int minTiles, int maxTiles) {
        if (poiData == null || mapPoiIds == null)
            return null;
        int centerTileX = (int) (centerPos.x / data.tileSize);
        int centerTileY = (int) (centerPos.y / data.tileSize);
        for (int attempt = 0; attempt < 300; attempt++) {
            int wx = centerTileX - maxTiles + random.nextInt(maxTiles * 2 + 1);
            int wy = centerTileY - maxTiles + random.nextInt(maxTiles * 2 + 1);
            int dx = wx - centerTileX, dy = wy - centerTileY;
            int distSq = dx * dx + dy * dy;
            if (distSq < minTiles * minTiles || distSq > maxTiles * maxTiles)
                continue;
            if (wx < 2 || wy < 2 || wx >= width - 2 || wy >= height - 2)
                continue;
            boolean tooClose = false;
            for (PointOfInterest other : getAllPointOfInterest()) {
                int px = (int) (other.getPosition().x / data.tileSize);
                int py = (int) (other.getPosition().y / data.tileSize);
                if (Math.abs(px - wx) <= 5 && Math.abs(py - wy) <= 5) {
                    tooClose = true;
                    break;
                }
            }
            if (tooClose)
                continue;
            PointOfInterest newPoint = new PointOfInterest(poiData, new Vector2(wx * data.tileSize, wy * data.tileSize), random);
            clearTerrain(wx, wy, 3);
            mapPoiIds.add(newPoint);
            refreshWorldMapMarkers();
            System.out.println("[World] placed " + poiData.name + " at tile " + wx + "," + wy
                    + " (" + (int) Math.sqrt(distSq) + " tiles from requested center)");
            return newPoint;
        }
        System.out.println("[World] no free spot for " + poiData.name + " within " + maxTiles + " tiles");
        return null;
    }

    public void refreshWorldMapMarkers() {
        if (biomeImage == null)
            return;
        rebakeMinimapAfterTerritoryControl();
        redrawAllPoiMarkers();
        // The fog overlay is a separate pixmap holding COPIES of biomeImage tiles - with fog of
        // war on it's what the minimap actually displays, so without this the fresh markers only
        // ever landed in the hidden biomeImage (user-reported: the Capitol's new castle icon
        // showed without fog of war but not with it). No-ops when fog of war is off.
        rebuildFogOfWarPixmap();
    }

    private void rebakeMinimapAfterTerritoryControl() {
        Pixmap pix = new Pixmap(width * data.miniMapTileSize, height * data.miniMapTileSize, Pixmap.Format.RGBA8888);
        pix.setColor(1, 0, 0, 1);
        pix.fill();
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                if (highestBiome(biomeMap[x][y]) >= data.GetBiomes().size()) {
                    Pixmap smallPixmap = createSmallPixmap(data.roadTileset.tilesetAtlas, data.roadTileset.tilesetName, 0);
                    pix.drawPixmap(smallPixmap, x * data.miniMapTileSize, y * data.miniMapTileSize);
                } else {
                    BiomeData biome = data.GetBiomes().get(highestBiome(biomeMap[x][y]));
                    int terrainIndex = terrainMap[x][y] & ~terrainMask;
                    if (terrainIndex > biome.terrain.length) {
                        Pixmap smallPixmap = createSmallPixmap(biome.tilesetAtlas, biome.tilesetName, 0);
                        pix.drawPixmap(smallPixmap, x * data.miniMapTileSize, y * data.miniMapTileSize);

                        terrainIndex -= biome.terrain.length;
                        terrainIndex--;
                        for (BiomeStructureData structData : biome.structures) {
                            if (terrainIndex >= structData.mappingInfo.length) {
                                terrainIndex -= structData.mappingInfo.length;
                                continue;
                            }
                            smallPixmap = createSmallPixmap(structData.structureAtlasPath, structData.mappingInfo[terrainIndex].name, 0);
                            pix.drawPixmap(smallPixmap, x * data.miniMapTileSize, y * data.miniMapTileSize);
                            break;
                        }
                    } else {
                        Pixmap smallPixmap = createSmallPixmap(biome.tilesetAtlas, biome.tilesetName, terrainIndex);
                        pix.drawPixmap(smallPixmap, x * data.miniMapTileSize, y * data.miniMapTileSize);
                    }
                }
            }
        }
        for (Map.Entry<String, Pair<Pixmap, HashMap<String, Pixmap>>> entry : pixmapHash.entrySet()) {
            try {
                entry.getValue().getLeft().dispose();
            } catch (Exception e) {
                //e.printStackTrace();
            }
            for (Map.Entry<String, Pixmap> pairEntry : entry.getValue().getRight().entrySet()) {
                try {
                    pairEntry.getValue().dispose();
                } catch (Exception e) {
                    //e.printStackTrace();
                }
            }
        }
        pixmapHash.clear();
        biomeImage = pix;
        try {
            drawPixmapNow(pix);
        } catch (Exception e) {
            //e.printStackTrace();
        }
    }

    // Per-tile version of rebakeMinimapAfterTerritoryControl()'s bake logic, for the three live
    // repaint paths (repaintBiomeAroundTown/neutralizeTerritoryOutsideRadius/claimWastelandRing).
    // Those used to stamp a flat index-0 base pixel per touched tile, wiping whatever detail the
    // minimap had there - terrain variants, structure pixels (mappingInfo), and the road overlay -
    // which is exactly the reported "the spread wipes out the mini-map's details as it grows"
    // (the game map itself was always fine; only this baked Pixmap was losing content). The full
    // post-sweep rebake already fixed this for world-gen time; this fixes it for every live
    // repaint since, by drawing the tile's REAL current content instead of a flat stamp. Reads
    // biomeMap/terrainMap directly, so callers must update those first, then call this.
    private void redrawMinimapTile(int x, int rawY) {
        redrawMinimapTile(x, rawY, null);
    }

    // decodeBiome, when non-null, is the biome whose terrain/structures tables this tile's
    // terrainMap value was ENCODED against, when that differs from the biome that owns the tile.
    // Needed by claimWastelandRing(): an expansion-claimed tile's value is written in colorless
    // index space (colorless's terrain table + the colorless-clone redirect structures), but
    // highestBiome() names the claiming COLOR, whose real structures[] tables are differently
    // sized for every AI color (e.g. white 3+7 entries vs colorless's 7+7) - decoding a
    // colorless-space value against the color's table draws the wrong structure pixel for most
    // values and NO structure pixel for values past the color's shorter table, which is exactly
    // the "flat minimap where it spreads" symptom this method exists to fix. The base ground
    // pixel still draws from the OWNING biome's tileset either way, so claimed territory keeps
    // reading as the owner's color on the minimap - only the structure lookup switches tables,
    // matching what the main map actually renders there (the kept waste layer's own art).
    private void redrawMinimapTile(int x, int rawY, BiomeData decodeBiome) {
        if (biomeImage == null)
            return;
        int mm = data.miniMapTileSize;
        if (highestBiome(biomeMap[x][rawY]) >= data.GetBiomes().size()) {
            biomeImage.drawPixmap(createSmallPixmap(data.roadTileset.tilesetAtlas, data.roadTileset.tilesetName, 0), x * mm, rawY * mm);
            return;
        }
        BiomeData biome = data.GetBiomes().get(highestBiome(biomeMap[x][rawY]));
        BiomeData decode = decodeBiome != null ? decodeBiome : biome;
        int terrainLength = decode.terrain == null ? 0 : decode.terrain.length;
        int terrainIndex = terrainMap[x][rawY] & ~terrainMask;
        if (terrainIndex > terrainLength) {
            biomeImage.drawPixmap(createSmallPixmap(biome.tilesetAtlas, biome.tilesetName, 0), x * mm, rawY * mm);
            terrainIndex -= terrainLength;
            terrainIndex--;
            if (decode.structures != null) {
                for (BiomeStructureData structData : decode.structures) {
                    if (terrainIndex >= structData.mappingInfo.length) {
                        terrainIndex -= structData.mappingInfo.length;
                        continue;
                    }
                    biomeImage.drawPixmap(createSmallPixmap(structData.structureAtlasPath, structData.mappingInfo[terrainIndex].name, 0), x * mm, rawY * mm);
                    break;
                }
            }
        } else {
            biomeImage.drawPixmap(createSmallPixmap(biome.tilesetAtlas, biome.tilesetName, terrainIndex), x * mm, rawY * mm);
        }
    }

    HashMap<String, Pair<Pixmap, HashMap<String, Pixmap>>> pixmapHash = new HashMap<>();

    private Pixmap createSmallPixmap(String tilesetName, String key, int i) {

        if (i > 2) i = 2;
        String tileSetNameWithIndex;
        if (i == 0)
            tileSetNameWithIndex = (key);
        else
            tileSetNameWithIndex = (key + "_" + i);
        if (!pixmapHash.containsKey(tilesetName)) {
            TextureAtlas.AtlasRegion region;
            TextureAtlas atlas = Config.instance().getAtlas(tilesetName);
            region = atlas.findRegion(tileSetNameWithIndex);
            TextureData data = region.getTexture().getTextureData();
            if (!data.isPrepared()) {
                data.prepare();
            }
            pixmapHash.put(tilesetName, Pair.of(data.consumePixmap(), new HashMap<>()));
        }
        Pair<Pixmap, HashMap<String, Pixmap>> pair = pixmapHash.get(tilesetName);
        if (!pair.getRight().containsKey(tileSetNameWithIndex)) {
            TextureAtlas atlas = Config.instance().getAtlas(tilesetName);
            TextureAtlas.AtlasRegion region = atlas.findRegion(tileSetNameWithIndex);
            int tileSize = data.tileSize;
            Pixmap smallPixmap = new Pixmap(data.miniMapTileSize, data.miniMapTileSize, Pixmap.Format.RGBA8888);
            smallPixmap.setColor(0, 0, 0, 0);
            smallPixmap.fill();
            smallPixmap.drawPixmap(pair.getLeft(), 0, 0, region.getRegionX(), region.getRegionY(), data.miniMapTileSize, data.miniMapTileSize);
            pair.getRight().put(tileSetNameWithIndex, smallPixmap);
        }
        return pair.getRight().get(tileSetNameWithIndex);

    }

    static class DrawInfo {
        Pixmap mapMarkerPixmap;
        int regionX;
        int regionY;
        int regionWidth;
        int regionHeight;
        int x;
        int y;
        int regionWidth1;
        int regionHeight1;
    }

    final Array<DrawInfo> storedInfo = new Array<>();

    private void drawPixmapLater(Pixmap mapMarkerPixmap, int regionX, int regionY, int regionWidth, int regionHeight, int x, int y, int regionWidth1, int regionHeight1) {
        DrawInfo info = new DrawInfo();
        info.mapMarkerPixmap = mapMarkerPixmap;
        info.regionX = regionX;
        info.regionY = regionY;
        info.regionWidth = regionWidth;
        info.regionHeight = regionHeight;
        info.x = x;
        info.y = y;
        info.regionWidth1 = regionWidth1;
        info.regionHeight1 = regionHeight1;
        storedInfo.add(info);
    }

    private void drawPixmapNow(Pixmap map) {
        for (DrawInfo info : storedInfo)
            map.drawPixmap(info.mapMarkerPixmap, info.regionX, info.regionY, info.regionWidth, info.regionHeight, info.x, info.y, info.regionWidth1, info.regionHeight1);
        storedInfo.clear();
    }

    public int getWidthInTiles() {
        return width;
    }

    public int getHeightInTiles() {
        return height;
    }

    public int getWidthInPixels() {
        return width * data.tileSize;
    }

    public int getHeightInPixels() {
        return height * data.tileSize;
    }

    public int getWidthInChunks() {
        return width / getChunkSize();
    }

    public int getHeightInChunks() {
        return height / getChunkSize();
    }

    public int getTileSize() {
        return data.tileSize;
    }

    public Pixmap getBiomeImage() {
        if (!isFogOfWarEnabled())
            return biomeImage;
        return fogOfWarPixmap != null ? fogOfWarPixmap : biomeImage;
    }

    // Fog of war needs both: the plane opts in via config.json ("fogOfWarEnabled": true, so this
    // never affects Shandalar or any other existing plane), AND the player has turned it on in
    // Settings (SettingData.fogOfWarEnabled, defaulting off). It's a Settings toggle rather than
    // an in-game HUD toggle because flipping it live mid-session didn't cleanly reset the
    // Known/Visible rendering state - Settings changes take effect from the next world load.
    private boolean isFogOfWarEnabled() {
        ConfigData configData = Config.instance().getConfigData();
        SettingData settingData = Config.instance().getSettingData();
        return configData != null && configData.fogOfWarEnabled
                && settingData != null && settingData.fogOfWarEnabled;
    }

    // Terrain Switch-Out (MOD_SCOPE.md #7, redesigned 2026-08-05): when a repaint changes which
    // biome owns a tile, translates whatever structure (mountain/rock/tree/water - anything from
    // that biome's own WFC-placed structures[], see world/biomes/*.json) or plain terrain-variant
    // ground texture was there into the new biome's own equivalent, instead of deleting it (which
    // is what made repainted territory look flat next to freshly-generated ground - see
    // MOD_CHANGELOG.md). Every biome's structures[].mappingInfo[].name already shares a mostly-
    // overlapping vocabulary ("tree"/"tree2"/"rock"/"mountain"/"water"/etc - the atlas region a
    // structure renders with IS this name, see BiomeTexture.generate()) - STRUCTURE_CATEGORY groups
    // the handful of biome-specific names (white's "mesa"/"plateau" are still mountain-like, etc)
    // so a biome missing the literal name (e.g. Blue has no literal "mountain") still gets a
    // thematically close swap instead of losing the feature. "rock" exists in every one of today's
    // 6 core biomes (verified by reading all 6 world/biomes/*.json files), so it's used as the
    // universal last-resort tier below.
    private static final Map<String, String> STRUCTURE_CATEGORY = new HashMap<>();
    static {
        for (String n : new String[]{"tree", "tree2", "tree3", "tree4", "tree5", "dead_tree", "dead_tree2", "dead_tree3", "pineapple"})
            STRUCTURE_CATEGORY.put(n, "TREE");
        for (String n : new String[]{"rock", "rock2", "rock3", "rock4", "crater", "hole"})
            STRUCTURE_CATEGORY.put(n, "ROCK");
        for (String n : new String[]{"mountain", "mesa", "plateau"})
            STRUCTURE_CATEGORY.put(n, "MOUNTAIN");
        STRUCTURE_CATEGORY.put("water", "WATER");
        for (String n : new String[]{"vine", "plant", "bush", "cactus", "cactus2", "cactus3"})
            STRUCTURE_CATEGORY.put(n, "FLORA");
        for (String n : new String[]{"lava", "muck", "dune", "dune2"})
            STRUCTURE_CATEGORY.put(n, "HAZARD");
    }
    private static final String UNIVERSAL_FALLBACK_CATEGORY = "ROCK";

    // [oldBiomeIndex][newBiomeIndex][oldRawIndex] -> translated, fully-encoded terrainMap value
    // (payload + collisionBit/isStructureBit already applied), or null meaning "leave this tile
    // alone" (only reachable if newBiome has zero structures at all - today just the not-yet-
    // built-out `player` placeholder biome, not currently reachable by any repaint call site).
    // Indexed by data.GetBiomes()'s stable list order, the same ints the 3 repaint methods below
    // already resolve (colorIndex/colorlessIndex/biomeIndex). Reset whenever generateNew() freshly
    // allocates biomeMap/terrainMap so a new game doesn't inherit a previous game's random picks.
    private Integer[][][] structureSwapCache;

    // Every (rawIndex, mapping) pair in biome whose structures[].mappingInfo[].name equals `name`.
    // rawIndex is the same base-1-relative index generateNew() assigns (terrain.length, then each
    // structures[] entry's mappingInfo.length, in array order - most biomes have two structures[]
    // entries, only green has one, so this walks the full list rather than assuming one).
    private static List<Pair<Integer, BiomeStructureData.BiomeStructureDataMapping>> candidatesByName(BiomeData biome, String name) {
        List<Pair<Integer, BiomeStructureData.BiomeStructureDataMapping>> result = new ArrayList<>();
        if (biome.structures == null)
            return result;
        int counter = 1 + (biome.terrain != null ? biome.terrain.length : 0);
        for (BiomeStructureData structure : biome.structures) {
            for (int i = 0; i < structure.mappingInfo.length; i++) {
                if (name.equals(structure.mappingInfo[i].name))
                    result.add(Pair.of(counter + i, structure.mappingInfo[i]));
            }
            counter += structure.mappingInfo.length;
        }
        return result;
    }

    // Same as candidatesByName(), matching by STRUCTURE_CATEGORY instead of the literal name.
    private static List<Pair<Integer, BiomeStructureData.BiomeStructureDataMapping>> candidatesForCategory(BiomeData biome, String category) {
        List<Pair<Integer, BiomeStructureData.BiomeStructureDataMapping>> result = new ArrayList<>();
        if (biome.structures == null)
            return result;
        int counter = 1 + (biome.terrain != null ? biome.terrain.length : 0);
        for (BiomeStructureData structure : biome.structures) {
            for (int i = 0; i < structure.mappingInfo.length; i++) {
                if (category.equals(STRUCTURE_CATEGORY.get(structure.mappingInfo[i].name)))
                    result.add(Pair.of(counter + i, structure.mappingInfo[i]));
            }
            counter += structure.mappingInfo.length;
        }
        return result;
    }

    // Exact name match, then thematic category, then the universal ROCK fallback - see
    // STRUCTURE_CATEGORY's comment for why ROCK never bottoms out for any of today's real biomes.
    // Returns null only when newBiome has no structures of any kind (today: just `player.json`).
    private Integer pickReplacement(BiomeStructureData.BiomeStructureDataMapping oldMapping, BiomeData newBiome) {
        List<Pair<Integer, BiomeStructureData.BiomeStructureDataMapping>> pool = candidatesByName(newBiome, oldMapping.name);
        if (pool.isEmpty()) {
            String category = STRUCTURE_CATEGORY.get(oldMapping.name);
            if (category != null)
                pool = candidatesForCategory(newBiome, category);
        }
        if (pool.isEmpty())
            pool = candidatesForCategory(newBiome, UNIVERSAL_FALLBACK_CATEGORY);
        if (pool.isEmpty())
            return null;
        Pair<Integer, BiomeStructureData.BiomeStructureDataMapping> chosen = pool.get(random.nextInt(pool.size()));
        int encoded = chosen.getLeft() | isStructureBit;
        if (chosen.getRight().collision)
            encoded |= collisionBit;
        return encoded;
    }

    // Builds the full oldBiome->newBiome translation table (see structureSwapCache's comment for
    // the encoding). Index 0 (plain ground) and plain terrain-variant indices carry over unchanged
    // (every one of today's 6 core biomes has exactly 2 terrain[] entries) except for newBiome's
    // own biome-wide collision flag, mirroring generateNew()'s own
    // "if (biome.collision) terrainMap[x][y] |= collisionBit;".
    private Integer[] buildStructureSwapTable(BiomeData oldBiome, BiomeData newBiome) {
        int oldTerrainCount = oldBiome.terrain != null ? oldBiome.terrain.length : 0;
        int newTerrainCount = newBiome.terrain != null ? newBiome.terrain.length : 0;
        int oldMax = 1 + oldTerrainCount;
        if (oldBiome.structures != null)
            for (BiomeStructureData structure : oldBiome.structures)
                oldMax += structure.mappingInfo.length;

        Integer[] table = new Integer[oldMax];
        int newBiomeCollision = newBiome.collision ? collisionBit : 0;
        table[0] = newBiomeCollision;
        for (int i = 1; i <= oldTerrainCount; i++)
            table[i] = (i <= newTerrainCount) ? (i | newBiomeCollision) : newBiomeCollision;

        if (oldBiome.structures != null) {
            int counter = 1 + oldTerrainCount;
            for (BiomeStructureData structure : oldBiome.structures) {
                for (int i = 0; i < structure.mappingInfo.length; i++)
                    table[counter + i] = pickReplacement(structure.mappingInfo[i], newBiome);
                counter += structure.mappingInfo.length;
            }
        }
        return table;
    }

    private Integer[] getStructureSwapTable(int oldBiomeIndex, int newBiomeIndex) {
        List<BiomeData> biomes = data.GetBiomes();
        if (structureSwapCache == null || structureSwapCache.length != biomes.size())
            structureSwapCache = new Integer[biomes.size()][biomes.size()][];
        if (structureSwapCache[oldBiomeIndex][newBiomeIndex] == null)
            structureSwapCache[oldBiomeIndex][newBiomeIndex] = buildStructureSwapTable(biomes.get(oldBiomeIndex), biomes.get(newBiomeIndex));
        return structureSwapCache[oldBiomeIndex][newBiomeIndex];
    }

    // Translates a tile's current encoded terrainMap value from oldBiomeIndex's index space to
    // newBiomeIndex's, used by all 3 repaint methods below in place of the old "just zero it"
    // behavior. Short-circuits unchanged if oldBiomeIndex is out of range (defensive) or equals
    // newBiomeIndex (repainting an already-target-color tile - avoids gratuitously reshuffling an
    // already-correct tile's structure choice). Returns null to mean "leave this tile's
    // terrainMap/biomeMap completely untouched" - callers must check for null before writing either.
    private Integer translateStructure(int oldBiomeIndex, int newBiomeIndex, int oldEncodedValue) {
        List<BiomeData> biomes = data.GetBiomes();
        if (oldBiomeIndex < 0 || oldBiomeIndex >= biomes.size() || oldBiomeIndex == newBiomeIndex)
            return oldEncodedValue;
        int oldRaw = oldEncodedValue & ~terrainMask;
        Integer[] table = getStructureSwapTable(oldBiomeIndex, newBiomeIndex);
        if (oldRaw < 0 || oldRaw >= table.length)
            return 0;
        return table[oldRaw];
    }

    // The WFC pattern cache built by generateNew()'s own structure-position loop
    // (structureDataMap, a Map<BiomeStructureData, BiomeStructure>) is keyed by *object identity*,
    // not by biome or content - that loop (and the similar per-color redirect-pattern precompute in
    // generateNew() itself, Territory Control #7) builds one BiomeStructure per (biome, structures[]
    // entry) pair, sized to *that biome's own* width/height, and stores it under the
    // BiomeStructureData object as the key. Sharing colorless's own BiomeStructureData objects
    // directly across multiple colors (a plain reference, like terrain/spriteNames can safely be -
    // neither is cached by identity) would mean every color sharing them, plus colorless itself,
    // racing to store multiple differently-sized BiomeStructure patterns under the same map keys,
    // with whichever finishes last silently winning for every other biome's per-tile lookups too -
    // a real, verified bug caught during this feature's first playtest, when a whole-biome content
    // swap did exactly that (structures came out visibly missing inside every color's claimed
    // circle - see MOD_CHANGELOG.md), not a hypothetical. Cloning gives every color its own distinct
    // BiomeStructureData objects (same content, different identity) via the existing
    // BiomeStructureData(BiomeStructureData) copy constructor, so each gets its own correctly-sized
    // WFC pattern instead of colliding with anyone else's.
    private static BiomeStructureData[] cloneStructures(BiomeStructureData[] source) {
        if (source == null)
            return null;
        BiomeStructureData[] clone = new BiomeStructureData[source.length];
        for (int i = 0; i < source.length; i++)
            clone[i] = new BiomeStructureData(source[i]);
        return clone;
    }

    // Lazily-built, persistent cache of a real biome's own native WFC structure pattern - separate
    // from generateNew()'s own structureDataMap, which is a LOCAL variable scoped to that one
    // method call and unreachable from anywhere else. claimWastelandRing() (daily territory
    // expansion, called repeatedly during actual gameplay, long after generateNew() has returned -
    // MOD_SCOPE.md #7) needs the same kind of native, full-scale pattern generateNew()'s own
    // placement pass already builds for every color's real structures[], but has no way to reach
    // it, so this builds (and remembers) one on first need instead. Built lazily rather than
    // eagerly during generateNew() specifically so this also works for a game LOADED from a save,
    // not just a freshly-generated one - loading never calls generateNew() at all, so anything only
    // populated there would silently be empty for a loaded game, exactly the situation that
    // surfaced this gap in the first place. ConcurrentHashMap, not a plain HashMap - a background
    // build (see getColorlessRedirectStructuresIfReady() below) can run concurrently with another
    // color's own background build, both touching this same map.
    private final Map<BiomeStructureData, BiomeStructure> nativeStructurePatternCache = new ConcurrentHashMap<>();

    private BiomeStructure getOrBuildNativePattern(BiomeStructureData structureData, int biomeWidth, int biomeHeight) {
        BiomeStructure cached = nativeStructurePatternCache.get(structureData);
        if (cached != null)
            return cached;
        BiomeStructure structure = new BiomeStructure(structureData, seed, biomeWidth, biomeHeight);
        try {
            structure.initialize();
        } catch (Exception ex) {
            ex.printStackTrace();
        }
        nativeStructurePatternCache.putIfAbsent(structureData, structure);
        return nativeStructurePatternCache.get(structureData);
    }

    // Same reasoning as nativeStructurePatternCache above: generateNew()'s own OpenSimplexNoise
    // instance is a LOCAL variable there, unreachable from claimWastelandRing() - lazily built once
    // (from the same seed field generateNew() itself uses) and reused for the rest of this game
    // session rather than constructed fresh per call, matching how the rest of this codebase always
    // treats noise as one shared instance for the whole map, never per-tile or per-call.
    private OpenSimplexNoise territoryNoise;

    private OpenSimplexNoise getTerritoryNoise() {
        if (territoryNoise == null)
            territoryNoise = new OpenSimplexNoise(seed);
        return territoryNoise;
    }

    // Lazily-built, persistent version of generateNew()'s own colorlessRedirectStructures (a
    // per-color clone of colorless's structures[], used by Pass B for any AI-color tile outside
    // that color's real castle radius) - same unreachability problem as the two caches above, same
    // fix. Shared by generateNew()'s Pass B AND claimWastelandRing() (daily territory expansion) so
    // the two can never independently drift on what "outside-radius content" means for a color -
    // daily expansion claims tiles that start out wasteland, so it always needs this redirect
    // content, never a color's real structures[] (see claimWastelandRing()'s own comment). Both this
    // and nativeStructurePatternCache are ConcurrentHashMaps, not plain HashMaps, because a cold
    // build can now run on a background thread (see getColorlessRedirectStructuresIfReady() below) -
    // up to 5 colors' worth of builds can be in flight at once, all touching these same two maps.
    private final Map<String, BiomeStructureData[]> colorlessRedirectStructureCache = new ConcurrentHashMap<>();
    // Tracks which colors currently have a background build running, so a burst of same-day calls
    // (processTerritoryExpansion() loops over all 5 colors every time the day counter advances)
    // doesn't kick off 5 redundant builds racing each other for the same color.
    private final Set<String> colorlessRedirectStructureBuildInFlight = ConcurrentHashMap.newKeySet();

    // The colors whose expansion claims carry colorless-redirect structure content: the 5 AI
    // colors AND "player" (town expansion, added 2026-08-08 - without it, player growth rings were
    // permanently bare ground with a forever-false "still building" log, found by the pre-commit
    // review).
    private boolean isClaimingColor(String color) {
        if ("player".equalsIgnoreCase(color))
            return true;
        for (String c : TerritoryControl.COLORS)
            if (c.equalsIgnoreCase(color))
                return true;
        return false;
    }

    // Blocking - only safe to call from somewhere that's already expected to take a while and isn't
    // running mid-gameplay, i.e. generateNew()'s Pass B (which has its own loading screen). Building
    // a fresh WFC pattern (BiomeStructure.initialize()) is a genuinely heavy computation - measured
    // in the low seconds per color during world-gen, where every other real structure pattern is
    // ALSO being built at the same time via parallel futures with the player already expecting to
    // wait. See getColorlessRedirectStructuresIfReady() for the non-blocking version gameplay-time
    // callers (claimWastelandRing()) must use instead - calling this one from the game's main/render
    // thread mid-play is exactly what caused a real, reported freeze (see MOD_CHANGELOG.md).
    private BiomeStructureData[] buildColorlessRedirectStructuresBlocking(String color) {
        if (!isTerritoryControlEnabled() || !isClaimingColor(color))
            return null;
        BiomeStructureData[] cached = colorlessRedirectStructureCache.get(color);
        if (cached != null)
            return cached;

        BiomeData colorlessBiomeRef = null;
        for (BiomeData b : data.GetBiomes())
            if ("waste".equalsIgnoreCase(b.name)) { colorlessBiomeRef = b; break; }
        if (colorlessBiomeRef == null || colorlessBiomeRef.structures == null)
            return null;
        // "player" builds at COLORLESS's own extent, not the player biome's - player claims (town
        // expansion) happen in the central waste region, which colorless's extent covers; the
        // player biome's own small spawn-centered extent wouldn't, and a pattern queried outside
        // its build extent yields no structures. claimWastelandRing() uses the matching geometry
        // for its position formula (see its playerClaim handling).
        BiomeData scaleBiome = "player".equalsIgnoreCase(color) ? colorlessBiomeRef : null;
        if (scaleBiome == null) {
            for (BiomeData b : data.GetBiomes())
                if (color.equalsIgnoreCase(b.name)) { scaleBiome = b; break; }
        }
        if (scaleBiome == null)
            return null;

        int scaleWidth = (int) Math.round(scaleBiome.width * (double) width);
        int scaleHeight = (int) Math.round(scaleBiome.height * (double) height);
        BiomeStructureData[] clone = cloneStructures(colorlessBiomeRef.structures);
        for (BiomeStructureData structureData : clone)
            getOrBuildNativePattern(structureData, scaleWidth, scaleHeight);
        colorlessRedirectStructureCache.putIfAbsent(color, clone);
        return colorlessRedirectStructureCache.get(color);
    }

    // Non-blocking - the only safe way for gameplay-time code (claimWastelandRing(), called from the
    // main/render thread whenever the in-game day counter advances) to get this. Returns the cached
    // result immediately if already built (the common case - after the very first use per color per
    // game session, every later call is an instant cache hit). If not yet built, kicks off (or lets
    // an already-running) background build proceed and returns null immediately rather than waiting
    // - the caller is expected to treat null as "not ready yet, try again later" (see
    // claimWastelandRing()'s own handling), never to block on it itself.
    private BiomeStructureData[] getColorlessRedirectStructuresIfReady(String color) {
        if (!isTerritoryControlEnabled() || !isClaimingColor(color))
            return null;
        BiomeStructureData[] cached = colorlessRedirectStructureCache.get(color);
        if (cached != null)
            return cached;
        if (colorlessRedirectStructureBuildInFlight.add(color)) {
            CompletableFuture.runAsync(() -> {
                try {
                    buildColorlessRedirectStructuresBlocking(color);
                } finally {
                    colorlessRedirectStructureBuildInFlight.remove(color);
                }
            });
        }
        return null;
    }

    // Called once, right after a save finishes loading (WorldSave.load()) - generateNew() never
    // runs for a loaded save, so without this, the very first color to need its redirect-structure
    // pattern during actual gameplay would be the one paying the (background, non-blocking, but
    // still real) cost of building it. Kicking all 5 off immediately, in parallel, in the background,
    // gives them a head start before the player has had time to advance a single in-game day - purely
    // an optimization, not required for correctness (getColorlessRedirectStructuresIfReady() already
    // handles "not ready yet" safely on its own either way).
    public void prewarmTerritoryControlCaches() {
        if (!isTerritoryControlEnabled())
            return;
        for (String color : TerritoryControl.COLORS)
            getColorlessRedirectStructuresIfReady(color);
    }

    /**
     * Repaints a circular area of terrain around a point to a named biome (e.g. "green") - used
     * live, mid-game, for an individual mage-captured town (see TerritoryControl.onMageArrived()).
     * Known, deliberate simplifications (see MOD_CHANGELOG.md):
     * - Hard replace, no autotile blending - generateBiomeSprite() blends multiple biome bits
     *   together for smooth edges, but this just overwrites biomeMap's bits outright, so the
     *   boundary of the recolored patch will look like a hard-edged block, not a natural
     *   transition. The real version needs the pre-split-zone approach described in #7.
     * - Clears any road bit the tile had, and doesn't avoid the town's own footprint - the whole
     *   radius, including under the town itself, gets recolored uniformly.
     * - Regenerates scattered decoration doodads (mapObjectIds) using the target biome's own
     *   spriteNames/density - see regenerateDoodadsInRadius(). Structures (mountains/rocks/trees/
     *   water) are reskinned to the new biome's closest equivalent in place, via
     *   translateStructure() above, rather than regenerated from scratch - see its own comment for
     *   why (structure placement is anchored to a biome's absolute map position, which has no
     *   well-defined answer for an arbitrary repainted patch elsewhere on the map).
     *
     * onChunkNeedsReload is called once per chunk overlapping the radius, separately from
     * onTileRepainted (which fires per-tile, for the ground texture patch) - doodad Actors are
     * cached per-chunk and only refresh on a full chunk reload, not a per-tile patch.
     */
    /**
     * Draws a road along a chain of POI waypoints, live, mid-game (Territory Control follow-up,
     * user spec 2026-08-09: a freshly-captured town gets a road to its color's nearest holding,
     * routed through whatever towns lie between - see TerritoryControl.connectCapturedTownByRoad()).
     * Each consecutive waypoint pair gets the exact same Bresenham line + biomeMap roadBit +
     * terrainMap=0 treatment as generateNew()'s own road pass, INCLUDING its [x][height - y] raw
     * index convention (one off from the height-y-1 convention the repaint methods use) - so a
     * runtime road overlaps pixel-for-pixel with the generated road network at shared towns.
     * Already-road tiles are skipped (no redundant redraws), which makes re-running a path over
     * existing roads nearly free. Doodads happening to sit on a new road tile are left alone
     * (structures clear via terrainMap=0; a stray bush on the roadside is acceptable).
     * Returns the number of tiles actually converted to road.
     */
    public int buildRoad(List<PointOfInterest> waypoints, BiConsumer<Integer, Integer> onTileRepainted) {
        if (data == null || biomeMap == null || terrainMap == null || waypoints == null || waypoints.size() < 2)
            return 0;
        long roadBit = 1L << data.GetBiomes().size();
        java.util.HashSet<Long> touched = new java.util.HashSet<>();
        for (int seg = 0; seg + 1 < waypoints.size(); seg++) {
            int startX = (int) waypoints.get(seg).getTilePosition(data.tileSize).x;
            int startY = (int) waypoints.get(seg).getTilePosition(data.tileSize).y;
            int x1 = (int) waypoints.get(seg + 1).getTilePosition(data.tileSize).x;
            int y1 = (int) waypoints.get(seg + 1).getTilePosition(data.tileSize).y;
            int dx = Math.abs(x1 - startX);
            int dy = Math.abs(y1 - startY);
            int sx = startX < x1 ? 1 : -1;
            int sy = startY < y1 ? 1 : -1;
            int err = dx - dy;
            int e2;
            for (int i = 0; i < 1000; i++) {
                if (!(startX < 0 || startY <= 0 || startX >= width || startY > height)) {
                    int rawY = height - startY;
                    if ((biomeMap[startX][rawY] & roadBit) == 0 || terrainMap[startX][rawY] != 0) {
                        biomeMap[startX][rawY] |= roadBit;
                        terrainMap[startX][rawY] = 0;
                        redrawMinimapTile(startX, rawY);
                        updateFogOfWarPixmap(startX, rawY);
                        touched.add((long) startX << 32 | (rawY & 0xffffffffL));
                    }
                }
                if (startX == x1 && startY == y1)
                    break;
                e2 = 2 * err;
                if (e2 > -dy) {
                    err = err - dy;
                    startX = startX + sx;
                } else if (e2 < dx) {
                    err = err + dx;
                    startY = startY + sy;
                }
            }
        }
        // Chunk-texture patches for every changed tile plus a 2-tile ring around it - a road
        // tile's neighbors blend against it, same neighbor-staleness reasoning as
        // repaintBiomeAroundTown()'s post-loop repaint.
        if (onTileRepainted != null && !touched.isEmpty()) {
            java.util.HashSet<Long> refreshed = new java.util.HashSet<>();
            for (long key : touched) {
                int tx = (int) (key >> 32);
                int rawY = (int) (key & 0xffffffffL);
                int wy = height - rawY - 1;
                for (int nx = tx - 2; nx <= tx + 2; nx++) {
                    if (nx < 0 || nx >= width)
                        continue;
                    for (int ny = wy - 2; ny <= wy + 2; ny++) {
                        if (ny < 0 || ny >= height)
                            continue;
                        if (refreshed.add((long) nx << 32 | (ny & 0xffffffffL)))
                            onTileRepainted.accept(nx, ny);
                    }
                }
            }
        }
        return touched.size();
    }

    public void repaintBiomeAroundTown(PointOfInterest point, String biomeName, int radius,
                                        BiConsumer<Integer, Integer> onTileRepainted,
                                        BiConsumer<Integer, Integer> onChunkNeedsReload) {
        if (point == null || data == null || biomeMap == null || terrainMap == null)
            return;
        List<BiomeData> biomes = data.GetBiomes();
        int biomeIndex = -1;
        for (int i = 0; i < biomes.size(); i++) {
            if (biomeName.equalsIgnoreCase(biomes.get(i).name)) {
                biomeIndex = i;
                break;
            }
        }
        if (biomeIndex < 0)
            return;
        BiomeData biome = biomes.get(biomeIndex);

        int centerWorldX = (int) (point.getPosition().x / data.tileSize);
        int centerWorldY = (int) (point.getPosition().y / data.tileSize);
        int radiusSq = radius * radius;
        int mm = data.miniMapTileSize;
        // Roads are one extra bit past the last real biome (see the road-drawing pass in
        // generateNew()). Preserved across a repaint by carrying existingRoadBit forward into the
        // new biomeMap value below, instead of skipping the tile entirely (the old behavior) - a
        // skipped tile's ground never updates to match its repainted surroundings, which is what
        // let roads visibly trace a stale-biome border once a chunk rebuilt (see MOD_CHANGELOG.md).
        // Safe unlike a bit-preservation idea that regressed elsewhere this round (ocean): road's
        // own tileset has exactly one region (index 0, verified in world.json - no terrain/
        // structures entries), and a road tile's terrainMap is always exactly 0 (set unconditionally
        // by the road-drawing pass), so "draw the road texture" is road's only possible meaning at
        // that index - no shared-terrainMap misinterpretation risk the way ocean's multi-region
        // tileset had.
        long roadBit = 1L << data.GetBiomes().size();
        // The blue-border fix, extended to PLAYER town captures (reported: the border was gone at
        // every AI color's territory but still present around the player's own captured towns).
        // Mechanism, same as claimWastelandRing()'s dual-bit write: a single-bit repainted tile
        // breaks its NEIGHBORS' waste layers' full-coverage checks in generateBiomeSprite(), which
        // promotes the first set bit - base/ocean, literal blue - to the base layer at the claim
        // edge. Keeping the waste bit underneath restores the symmetry. Only safe for "player"
        // over former waste specifically: player's terrain/structure table layout is an exact
        // colorless clone (1+2+7+7 regions, verified), so the kept waste layer decodes the
        // translated player-space terrainMap value coherently - an AI color's differently-sized
        // tables would misinterpret it, and AI captures get engulfed by their color's own dual-bit
        // expansion anyway (which is why the border never showed there).
        int colorlessIdx = -1;
        for (int i = 0; i < biomes.size(); i++)
            if ("waste".equalsIgnoreCase(biomes.get(i).name)) { colorlessIdx = i; break; }
        boolean keepWasteUnder = "player".equalsIgnoreCase(biomeName) && colorlessIdx >= 0;
        // Round 98 (user spec 2026-09-03): water bodies are never painted over. A water tile carries no
        // biome bit at all (highestBiome() then returns a negative index and translateStructure() passes
        // the tile through unchanged - straight into the new biome), or belongs to a biome named
        // ocean/water if the plane defines one.
        int oceanIdx = -1;
        for (int i = 0; i < biomes.size(); i++)
            if ("ocean".equalsIgnoreCase(biomes.get(i).name) || "water".equalsIgnoreCase(biomes.get(i).name)) { oceanIdx = i; break; }
        for (int wx = centerWorldX - radius; wx <= centerWorldX + radius; wx++) {
            if (wx < 0 || wx >= width)
                continue;
            int dx = wx - centerWorldX;
            for (int wy = centerWorldY - radius; wy <= centerWorldY + radius; wy++) {
                if (wy < 0 || wy >= height)
                    continue;
                int dy = wy - centerWorldY;
                if (dx * dx + dy * dy > radiusSq)
                    continue;

                int rawY = height - wy - 1;
                long existingRoadBit = biomeMap[wx][rawY] & roadBit;

                int oldBiomeIndex = highestBiome(biomeMap[wx][rawY]); // read before overwriting below
                if ((biomeMap[wx][rawY] & ~roadBit) == 0L || oldBiomeIndex == oceanIdx)
                    continue; // round 98: water stays water
                Integer newTerrain = translateStructure(oldBiomeIndex, biomeIndex, terrainMap[wx][rawY]);
                if (newTerrain == null)
                    continue;
                long wasteUnderBit = (keepWasteUnder && oldBiomeIndex == colorlessIdx) ? (1L << colorlessIdx) : 0L;
                biomeMap[wx][rawY] = existingRoadBit | wasteUnderBit | (1L << biomeIndex);
                terrainMap[wx][rawY] = newTerrain;

                redrawMinimapTile(wx, rawY); // real content (variants/structures/roads), not a flat stamp - see its comment
                updateFogOfWarPixmap(wx, rawY);
            }
        }
        // Chunk-texture patches deferred to after the loop, radius+2 to also catch every border
        // tile whose neighborhood changed - same neighbor-blend staleness fix as
        // claimWastelandRing()'s own post-loop repaint (see its comment): patching per tile
        // mid-loop drew each tile against half-repainted neighbors and never revisited it,
        // leaving the recolored disc visibly blocky until the player walked over it.
        if (onTileRepainted != null) {
            int repaintRadiusSq = (radius + 2) * (radius + 2);
            for (int wx = centerWorldX - radius - 2; wx <= centerWorldX + radius + 2; wx++) {
                if (wx < 0 || wx >= width)
                    continue;
                int dx = wx - centerWorldX;
                for (int wy = centerWorldY - radius - 2; wy <= centerWorldY + radius + 2; wy++) {
                    if (wy < 0 || wy >= height)
                        continue;
                    int dy = wy - centerWorldY;
                    if (dx * dx + dy * dy > repaintRadiusSq)
                        continue;
                    onTileRepainted.accept(wx, wy);
                }
            }
        }

        regenerateDoodadsInRadius(centerWorldX, centerWorldY, 0, radius, biome);

        if (onChunkNeedsReload != null) {
            int chunkSize = getChunkSize();
            int minChunkX = Math.floorDiv(centerWorldX - radius, chunkSize);
            int maxChunkX = Math.floorDiv(centerWorldX + radius, chunkSize);
            int minChunkY = Math.floorDiv(centerWorldY - radius, chunkSize);
            int maxChunkY = Math.floorDiv(centerWorldY + radius, chunkSize);
            for (int cx = minChunkX; cx <= maxChunkX; cx++)
                for (int cy = minChunkY; cy <= maxChunkY; cy++)
                    onChunkNeedsReload.accept(cx, cy);
        }

        // The per-tile drawPixmap loop above can partially paint over a nearby POI's own marker
        // icon on the minimap - markers are only ever baked in once (world-gen's own placement loop,
        // or the one-time post-sweep redrawAllPoiMarkers() call in generateNew()), never refreshed
        // for a live, mid-game repaint like this one until now. Confirmed via grep that
        // redrawAllPoiMarkers() previously had exactly one caller (the world-gen-time sweep) -
        // covering it here too fixes both an AI mage's capture (onMageArrived()) and the player's
        // own, since both go through this same method. Guarded the same way the loop above already
        // guards its own biomeImage use.
        if (biomeImage != null)
            redrawAllPoiMarkers();
    }

    /**
     * Territory Control (MOD_SCOPE.md #7): reassigns every tile belonging to the named color biome
     * to the "waste" (colorless) biome, EXCEPT within radiusTiles of keepCenter - the ownership half
     * of what used to also be a content-reskinning pass, before the spatially-aware placement
     * redesign (see MOD_CHANGELOG.md). Deliberately does NOT touch terrainMap: generateNew()'s
     * placement pass already computed every tile's content using colorless's own recipe wherever
     * it's farther than radiusTiles from this color's real castle (that's the whole point of the
     * redesign - content is native from the start, never reconstructed after the fact) - rewriting
     * it here via translateStructure() would misinterpret an already-colorless-shaped index as if
     * it were still this color's own, the same class of bug the ocean-bit regression was earlier
     * this session. Only `biomeMap`'s ownership bit, the minimap pixel, and fog-of-war need
     * touching here. Used once, right after normal generateNew() finishes (unlike
     * repaintBiomeAroundTown()'s live, mid-game single-town use, world-gen hasn't produced a live
     * WorldStage/WorldBackground yet, so onTileRepainted/onChunkNeedsReload are typically null here
     * - nothing needs a live-refresh callback before the scene has even loaded).
     * <p>
     * Deliberately scans the *entire* map rather than a precomputed bounding box: the original
     * per-biome painting loop in generateNew() tracks x/y as raw array indices, while this method
     * (like repaintBiomeAroundTown()) works in world/game tile coordinates via getBiome()'s own
     * height-y-1 flip - reusing that already-correct accessor sidesteps re-deriving the bounding
     * box's own flip conversion by hand, at the cost of a full-map scan. Acceptable for a one-time
     * post-generation pass (not a per-frame or even per-capture operation).
     */
    public void neutralizeTerritoryOutsideRadius(String colorBiomeName, Vector2 keepCenter, int radiusTiles,
                                                  BiConsumer<Integer, Integer> onTileRepainted,
                                                  BiConsumer<Integer, Integer> onChunkNeedsReload) {
        if (data == null || biomeMap == null || terrainMap == null)
            return;
        List<BiomeData> biomes = data.GetBiomes();
        int colorIndex = -1, colorlessIndex = -1;
        for (int i = 0; i < biomes.size(); i++) {
            if (colorBiomeName.equalsIgnoreCase(biomes.get(i).name))
                colorIndex = i;
            if ("waste".equalsIgnoreCase(biomes.get(i).name))
                colorlessIndex = i;
        }
        if (colorIndex < 0 || colorlessIndex < 0)
            return;
        BiomeData colorlessBiome = biomes.get(colorlessIndex);

        int centerTileX = (int) (keepCenter.x / data.tileSize);
        int centerTileY = (int) (keepCenter.y / data.tileSize);
        int radiusSq = radiusTiles * radiusTiles;
        long roadBit = 1L << biomes.size();
        int mm = data.miniMapTileSize;
        int tilesReassigned = 0;

        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        for (int wx = 0; wx < width; wx++) {
            for (int wy = 0; wy < height; wy++) {
                // Road-bit mask (2026-08-15 review finding): getBiome() is unmasked, and the road
                // pseudo-bit sits above every real biome index, so highestBiome() on a road tile
                // this color owns would return the road index, never colorIndex - the tile would
                // never qualify for reassignment below and would keep this color's ownership bit
                // forever. Same bug class already fixed in claimWastelandRing(); mask for
                // CLASSIFICATION only, same as there - the write below already preserves the road
                // bit itself via existingRoadBit.
                if (highestBiome(getBiome(wx, wy) & ~roadBit) != colorIndex)
                    continue;
                int dx = wx - centerTileX;
                int dy = wy - centerTileY;
                if (dx * dx + dy * dy <= radiusSq)
                    continue; // close enough to the castle - stays this color

                int rawY = height - wy - 1;
                long existingRoadBit = biomeMap[wx][rawY] & roadBit; // preserve roads, same as repaintBiomeAroundTown()
                biomeMap[wx][rawY] = existingRoadBit | (1L << colorlessIndex);
                tilesReassigned++;

                redrawMinimapTile(wx, rawY); // real content, not a flat stamp - see its comment
                updateFogOfWarPixmap(wx, rawY);

                if (onTileRepainted != null)
                    onTileRepainted.accept(wx, wy);
                minX = Math.min(minX, wx); maxX = Math.max(maxX, wx);
                minY = Math.min(minY, wy); maxY = Math.max(maxY, wy);
            }
        }
        System.out.println("[TerritoryControl] " + colorBiomeName + ": " + tilesReassigned + " tile(s) reassigned to colorless outside radius " + radiusTiles);

        if (onChunkNeedsReload != null && minX <= maxX) {
            int chunkSize = getChunkSize();
            int minChunkX = Math.floorDiv(minX, chunkSize);
            int maxChunkX = Math.floorDiv(maxX, chunkSize);
            int minChunkY = Math.floorDiv(minY, chunkSize);
            int maxChunkY = Math.floorDiv(maxY, chunkSize);
            for (int cx = minChunkX; cx <= maxChunkX; cx++)
                for (int cy = minChunkY; cy <= maxChunkY; cy++)
                    onChunkNeedsReload.accept(cx, cy);
        }
    }

    /**
     * Territory Control (MOD_SCOPE.md #7) expansion, weighted-pull model (2026-08-08 redesign per
     * user: "if two borders meet, the one closer to that color's capitol overrides the other...
     * take into account distance from Castle, Capitol and nearby towns"). Claims tiles in the
     * annulus [innerRadiusTiles, outerRadiusTiles] around center for the named color.
     * <p>
     * allPullSources: every faction's influence sources, keyed by color name plus "player". Each
     * source is {tileX, tileY, weightMultiplier, hardProtectRadiusTiles}. A faction's PULL on a
     * tile is min over its sources of dist*weight - lower is stronger, so a castle (weight 1.0)
     * projects further than a captured town (1.3), and a forward capital/town bends the border
     * outward around itself. Rules per tile:
     * <ul>
     * <li>WASTELAND tile: claimed if no rival's pull is strictly stronger than mine (ties: first
     * claimer this tick keeps it, same as the old Voronoi).</li>
     * <li>Tile owned by another faction (an AI color or the player): TAKEN OVER only if my pull is
     * strictly stronger than the owner's - borders are contested, not first-come-forever. Both
     * sides compute identical pulls, so ownership converges (no daily flip-flop): the stronger
     * side takes the tile once and the weaker side can never take it back unless the sources
     * themselves change (a town changes hands, a capital falls...).</li>
     * <li>Hard protection: a tile within ANY rival source's hardProtectRadius is never touched -
     * castle keeps stay whole, and every town (AI or player) keeps at least the inner HALF of its
     * current territory radius (the "towns can lose up to 50% of their ground" user rule).</li>
     * <li>base/ocean and anything not in allPullSources stays untouchable, as ever.</li>
     * </ul>
     * The player's old special-cases are gone: Spawn no longer projects any protection (the
     * central-teleporter bubble is deliberately claimable now, per user), and player towns follow
     * the exact same pull + 50%-hard-protection rules as AI towns.
     * <p>
     * Called every in-game day a color's territory grows (unlike neutralizeTerritoryOutsideRadius(),
     * a one-time world-gen-time sweep) - scoped to a bounding box around center, not a full-map scan.
     */
    public int claimWastelandRing(String colorBiomeName, Vector2 center,
                                    Map<String, List<float[]>> allPullSources,
                                    int innerRadiusTiles, int outerRadiusTiles,
                                    BiConsumer<Integer, Integer> onTileRepainted,
                                    BiConsumer<Integer, Integer> onChunkNeedsReload) {
        return claimWastelandRing(colorBiomeName, center, allPullSources, innerRadiusTiles,
                outerRadiusTiles, onTileRepainted, onChunkNeedsReload, null);
    }

    // outClaimedTiles (2026-08-13 FoW fix): lets the player-Capitol expansion reveal exactly the
    // ground it actually claimed rather than the whole geometric radius disc (which included
    // ocean and rival-owned land, inflating the fully-explored counter far past what was really
    // owned or seen - the "map fully explored fired too early" bug). Packed with packTile(wx, wy)
    // in WORLD coordinates, same encoding the repaint dedup below already uses. Null = don't
    // collect (AI colors never reveal, so they pass null via the delegate above).
    public int claimWastelandRing(String colorBiomeName, Vector2 center,
                                    Map<String, List<float[]>> allPullSources,
                                    int innerRadiusTiles, int outerRadiusTiles,
                                    BiConsumer<Integer, Integer> onTileRepainted,
                                    BiConsumer<Integer, Integer> onChunkNeedsReload,
                                    Set<Long> outClaimedTiles) {
        if (data == null || biomeMap == null || terrainMap == null)
            return 0;
        List<BiomeData> biomes = data.GetBiomes();
        int colorIndex = -1, colorlessIndex = -1;
        // Every biome index that can appear as a CONTESTABLE owner, mapped to its pull-source key
        // in allPullSources ("white".."green" plus "player"). Anything else (base/ocean) stays
        // untouchable.
        Map<Integer, String> contestableOwners = new HashMap<>();
        for (int i = 0; i < biomes.size(); i++) {
            String biomeName = biomes.get(i).name;
            if (colorBiomeName.equalsIgnoreCase(biomeName))
                colorIndex = i;
            if ("waste".equalsIgnoreCase(biomeName))
                colorlessIndex = i;
            if (biomeName != null && allPullSources.containsKey(biomeName.toLowerCase()))
                contestableOwners.put(i, biomeName.toLowerCase());
        }
        if (colorIndex < 0 || colorlessIndex < 0)
            return 0;
        List<float[]> mySources = allPullSources.get(colorBiomeName.toLowerCase());
        if (mySources == null || mySources.isEmpty())
            return 0; // no sources -> no pull -> nothing to claim with
        BiomeData colorBiome = biomes.get(colorIndex);
        BiomeData colorlessBiome = biomes.get(colorlessIndex);
        // The geometry the structure-position formula runs in must match the scale the redirect
        // WFC pattern was BUILT at (getOrBuildColorlessRedirectStructures()): each AI color's
        // pattern is built at that color's own biome extent, but "player" claims (town expansion)
        // use COLORLESS's extent instead - player towns live in the central waste region, which
        // colorless's own extent covers, while the player biome's own tiny spawn-centered extent
        // wouldn't (a pattern queried outside its build extent returns no structures at all).
        boolean playerClaim = "player".equalsIgnoreCase(colorBiomeName);
        BiomeData structureGeometryBiome = playerClaim ? colorlessBiome : colorBiome;
        int colorBiomeXStart = (int) Math.round(structureGeometryBiome.startPointX * (double) width);
        int colorBiomeYStart = (int) Math.round(structureGeometryBiome.startPointY * (double) height);
        int colorBiomeWidth = (int) Math.round(structureGeometryBiome.width * (double) width);
        int colorBiomeHeight = (int) Math.round(structureGeometryBiome.height * (double) height);
        // Same per-color clone of colorless's structures[] that generateNew()'s Pass B redirects
        // outside-castle-radius tiles to (MOD_SCOPE.md #7) - every tile this method claims starts
        // out wasteland by definition (the claim condition below requires highestBiome == colorless),
        // so it always needs this redirect content, never colorBiome's own real structures[] (those
        // are reserved for the small kept circle around the castle, which daily expansion never
        // touches - it only grows outward from the edge of what's already claimed).
        // Non-blocking lookup - this method runs on the game's main/render thread every time the day
        // counter advances, and building a fresh WFC pattern is a genuinely heavy computation; a
        // blocking wait here is exactly what caused a real, reported freeze the first time a color's
        // pattern was needed on a loaded save (generateNew() never runs for a loaded save, so nothing
        // had pre-built it). If not ready yet, this call still claims the tiles with correct ground/
        // collision, just without decorative structures - a background build is already in flight (see
        // getColorlessRedirectStructuresIfReady()) and every later call, including tomorrow's for the
        // next ring outward, will find it cached.
        BiomeStructureData[] redirectStructures = getColorlessRedirectStructuresIfReady(colorBiomeName);
        if (redirectStructures == null)
            System.out.println("[TerritoryControl] " + colorBiomeName + ": redirect structure pattern still building in the background, claiming this ring with plain ground for now");
        int tilesClaimed = 0, tilesWithStructure = 0;

        int centerTileX = (int) (center.x / data.tileSize);
        int centerTileY = (int) (center.y / data.tileSize);
        int innerRadiusSq = innerRadiusTiles * innerRadiusTiles;
        int outerRadiusSq = outerRadiusTiles * outerRadiusTiles;
        long roadBit = 1L << biomes.size();
        int mm = data.miniMapTileSize;

        // Flatten the rival sources once (skipping my own color's list - those are mySources).
        // Each becomes {tileX, tileY, weightSquared, hardProtectRadiusSquared, ownerOrdinal} with
        // ownerOrdinal indexing rivalKeys, so the per-tile loop below can track the owner's pull
        // and the best rival pull in a single pass.
        List<String> rivalKeys = new ArrayList<>();
        List<float[]> rivalFlat = new ArrayList<>();
        for (Map.Entry<String, List<float[]>> entry : allPullSources.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(colorBiomeName))
                continue;
            int ordinal = rivalKeys.size();
            rivalKeys.add(entry.getKey());
            for (float[] source : entry.getValue())
                rivalFlat.add(new float[]{source[0], source[1], source[2] * source[2], source[3] * source[3], ordinal});
        }

        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        // Tiles this call actually claims (passed the geometric AND nearest-anchor checks below) -
        // regenerateDoodadsInRadius() used to independently re-derive "which tiles belong to this
        // color" using only the geometric annulus, silently ignoring the nearest-anchor check this
        // loop applies - so a tile losing that check to a nearer rival (another AI castle, or a
        // player town) still got this color's doodads placed on it, while ground ownership correctly
        // stayed with the rival (or colorless). A real, reported bug: doodads visibly spread into a
        // chord-shaped section of the circle - exactly the shape a straight Voronoi boundary between
        // two anchors would cut - while the ground there never changed color. Collecting the actual
        // claimed set here and handing it directly to doodad placement (see below) means there's only
        // ever one definition of "does this color own this tile," not two that can drift apart.
        Set<Long> claimedTiles = new HashSet<>();
        // Per-column analytic y-range (2026-08-25 perf fix: "freeze at the end of every day, gets
        // worse the longer a playthrough runs"). This loop used to scan the FULL (2*outerRadiusTiles
        // +1)^2 bounding box and reject non-ring tiles one at a time via the distSq check below -
        // O(outerRadiusTiles^2) despite the surrounding comments' claim that only the new ring is
        // scanned. outerRadiusTiles is a color's/town's live territory radius, which grows toward a
        // 450-tile cap essentially never reached in a normal session (observed climbing 21->107,
        // >5x, over one 13-week playtest, i.e. >25x more wasted iteration by the end) - and this
        // method runs synchronously on the render thread every day a color/town/Capitol expands, so
        // that growing cost was paid as a direct, worsening frame stall at every day-rollover.
        // For each column (dx), the valid y-range is the row-solve of x^2+y^2<=outerRadiusSq (and,
        // for the annulus case where |dx| is small enough to also be inside the inner circle, MINUS
        // the row-solve of x^2+y^2<innerRadiusSq, splitting the column into two segments straddling
        // the inner hole). Iterating only those segments turns the per-call cost into O(ring area)
        // instead of O(bounding box area) - cheap even as the radius grows into the hundreds. The
        // original distSq check is kept as a defensive no-op safety net, not load-bearing for
        // correctness of which tiles get visited anymore.
        for (int wx = Math.max(0, centerTileX - outerRadiusTiles); wx <= Math.min(width - 1, centerTileX + outerRadiusTiles); wx++) {
            int dx = wx - centerTileX;
            int dxSq = dx * dx;
            if (dxSq > outerRadiusSq)
                continue; // column doesn't intersect the outer circle at all
            int outerY = (int) Math.floor(Math.sqrt((double) (outerRadiusSq - dxSq)));
            int segStart1 = centerTileY - outerY, segEnd1 = centerTileY + outerY;
            int segStart2 = 1, segEnd2 = 0; // sentinel: empty (segStart2 > segEnd2) unless overwritten below
            if (dxSq < innerRadiusSq) {
                // this column also punches through the inner circle - split into two segments
                int innerY = (int) Math.ceil(Math.sqrt((double) (innerRadiusSq - dxSq)));
                segEnd1 = centerTileY - innerY;
                segStart2 = centerTileY + innerY;
                segEnd2 = centerTileY + outerY;
            }
            for (int seg = 0; seg < 2; seg++) {
                int segStart = seg == 0 ? segStart1 : segStart2;
                int segEnd = seg == 0 ? segEnd1 : segEnd2;
            for (int wy = Math.max(0, segStart); wy <= Math.min(height - 1, segEnd); wy++) {
                int dy = wy - centerTileY;
                int distSq = dx * dx + dy * dy;
                if (distSq > outerRadiusSq || distSq < innerRadiusSq)
                    continue;
                // Road bit masked OFF for ownership classification (2026-08-15 FoW fix): a road
                // tile's highestBiome() is the road pseudo-index (one above every real biome), so
                // roads always fell through the "non-faction biome - untouchable" gate below and
                // were NEVER claimed - meaning they never received the player bit, so
                // isPersistentlyRevealed() kept them at FoW stage 2 (hazed) forever inside
                // otherwise fully-revealed player territory (user report: hazed road strips and
                // hazed rings around neutral towns' own world-gen 3x3 road stamps). Masking lets
                // the tile contest by its UNDERLYING owner; the road bit itself is preserved on
                // the claim write (isRoadTile branch below), so rendering and the road-vs-offroad
                // speed logic still see a road.
                long rawBiomeBits = getBiome(wx, wy);
                boolean isRoadTile = (rawBiomeBits & roadBit) != 0;
                int ownerIndex = highestBiome(rawBiomeBits & ~roadBit);
                if (ownerIndex == colorIndex)
                    continue; // already mine
                boolean isWasteland = ownerIndex == colorlessIndex;
                String ownerKey = isWasteland ? null : contestableOwners.get(ownerIndex);
                if (!isWasteland && ownerKey == null)
                    continue; // base/ocean or some non-faction biome - untouchable

                // My pull: min over my sources of distSq*weightSq (monotonic in dist*weight).
                float myPullSq = Float.MAX_VALUE;
                for (float[] source : mySources) {
                    float sdx = wx - source[0], sdy = wy - source[1];
                    float pull = (sdx * sdx + sdy * sdy) * source[2] * source[2];
                    if (pull < myPullSq)
                        myPullSq = pull;
                }
                // Rivals: hard protection, the owner's pull, and the best rival pull, one pass.
                boolean hardProtected = false;
                float ownerPullSq = Float.MAX_VALUE, bestRivalPullSq = Float.MAX_VALUE;
                int ownerOrdinal = ownerKey == null ? -1 : rivalKeys.indexOf(ownerKey);
                for (float[] rival : rivalFlat) {
                    float rdx = wx - rival[0], rdy = wy - rival[1];
                    float rDistSq = rdx * rdx + rdy * rdy;
                    if (rival[3] > 0 && rDistSq <= rival[3]) {
                        hardProtected = true; // inside a castle keep or a town's inner-half - inviolable
                        break;
                    }
                    float pull = rDistSq * rival[2];
                    if (pull < bestRivalPullSq)
                        bestRivalPullSq = pull;
                    if ((int) rival[4] == ownerOrdinal && pull < ownerPullSq)
                        ownerPullSq = pull;
                }
                if (hardProtected)
                    continue;
                if (isWasteland) {
                    if (bestRivalPullSq < myPullSq)
                        continue; // a strictly stronger rival will claim this on its own tick
                } else {
                    if (myPullSq >= ownerPullSq)
                        continue; // takeover needs a STRICTLY stronger pull than the current owner
                }

                int rawY = height - wy - 1;
                long existingRoadBit = biomeMap[wx][rawY] & roadBit; // preserve roads, same as repaintBiomeAroundTown()
                if (isRoadTile) {
                    // Ownership-bits-only claim for road tiles (2026-08-15 FoW fix, see the
                    // classification comment above): flip the owner bits so the player bit (and
                    // AI colors' own bits, same code path) actually lands on roads, but leave
                    // EVERYTHING else about a road tile alone - terrainMap stays 0 (the invariant
                    // the road-drawing passes set and rely on), no structure/doodad generation
                    // (skipped via not joining claimedTiles), and no redrawMinimapTile (the
                    // minimap's road pixel must stay a road pixel). updateFogOfWarPixmap still
                    // runs so the tile's fog stage updates immediately.
                    biomeMap[wx][rawY] = existingRoadBit | (1L << colorlessIndex) | (1L << colorIndex);
                    tilesClaimed++;
                    updateFogOfWarPixmap(wx, rawY);
                    minX = Math.min(minX, wx); maxX = Math.max(maxX, wx);
                    minY = Math.min(minY, wy); maxY = Math.max(maxY, wy);
                    continue;
                }
                // The colorless bit is KEPT underneath the color's own bit (unlike repaintBiome-
                // AroundTown()'s single-bit overwrite) - this is the actual fix for the long-
                // standing "blue border" artifact at expansion boundaries and along roads.
                // Mechanism, derived from generateBiomeSprite() directly: the renderer draws every
                // set bit's layer bottom-up, needs at least one full-coverage base layer, and if
                // none qualifies it promotes the FIRST set bit - which is base/ocean, literal blue
                // water - to full. A single-bit claimed tile breaks its NEIGHBORS' waste layers'
                // full-neighborhood checks (and road tiles, skipped by this loop entirely, keep
                // ocean|waste|road while everything around them loses waste), so wherever a nearby
                // tile's own base coverage failed, ocean got promoted and rendered as blue flanks.
                // Keeping waste underneath restores neighbor-bit symmetry: the waste layer renders
                // as a genuine full base under the color's edge pieces - the exact multi-bit
                // blending mechanism stock world-gen boundaries already rely on - so ocean never
                // gets promoted, and the claim edge reads as a soft transition instead of a hard
                // cut. Ownership is unaffected: highestBiome() still reports the color, since every
                // AI color's biome index is above colorless's (world.json order: base, colorless,
                // then the 5 colors, then player). The ocean bit itself stays dropped - re-adding
                // THAT was the reverted checkerboard regression (see MOD_CHANGELOG.md's ocean-bit
                // entry); this keeps a bit whose own terrain table matches the tile's
                // colorless-recipe terrain values exactly (redirectStructures ARE colorless
                // clones), so there's no shared-terrainMap misinterpretation risk.
                biomeMap[wx][rawY] = existingRoadBit | (1L << colorlessIndex) | (1L << colorIndex);

                // Native computation, not a reskin - mirrors generateNew()'s Pass B exactly (same
                // terrain-variant noise formula/seed via getTerritoryNoise(), same structure-position
                // formula, using this color's own colorBiomeXStart/YStart/Width/Height since
                // redirectStructures' WFC pattern was built at that same scale) so a tile claimed by
                // daily expansion comes out exactly as dense/varied as one claimed at world-gen time.
                // translateStructure() (reskinning whatever single structure wasteland's own WFC
                // pattern had already placed at this spot, 1:1) could only preserve or drop existing
                // density, never add it the way native computation can - this was the actual root
                // cause of "black skips an area with the fill" / "white looks flat on the minimap
                // where it spreads": daily expansion still had the exact density cap Pass B was built
                // to eliminate for the initial circle, just never extended to it (MOD_SCOPE.md #7).
                int terrainCounter = 1;
                terrainMap[wx][rawY] = 0;
                if (colorlessBiome.terrain != null) {
                    for (BiomeTerrainData terrain : colorlessBiome.terrain) {
                        float terrainNoise = ((float) getTerritoryNoise().eval(wx / (float) width * (data.noiseZoomBiome * terrain.resolution), rawY / (float) height * (data.noiseZoomBiome * terrain.resolution)) + 1) / 2;
                        if (terrainNoise >= terrain.min && terrainNoise <= terrain.max) {
                            terrainMap[wx][rawY] = terrainCounter;
                        }
                        terrainCounter++;
                    }
                }
                if (colorBiome.collision)
                    terrainMap[wx][rawY] |= collisionBit;
                if (redirectStructures != null) {
                    for (BiomeStructureData structureData : redirectStructures) {
                        BiomeStructure structure = getOrBuildNativePattern(structureData, colorBiomeWidth, colorBiomeHeight);
                        int structureXStart = wx - (colorBiomeXStart - colorBiomeWidth / 2) - (int) ((structureData.x * colorBiomeWidth) - (structureData.width * colorBiomeWidth / 2));
                        int structureYStart = rawY - (colorBiomeYStart - colorBiomeHeight / 2) - (int) ((structureData.y * colorBiomeHeight) - (structureData.height * colorBiomeHeight / 2));

                        int structureIndex = structure.objectID(structureXStart, structureYStart);
                        if (structureIndex >= 0) {
                            terrainMap[wx][rawY] = terrainCounter + structureIndex;
                            if (structure.collision(structureXStart, structureYStart))
                                terrainMap[wx][rawY] |= collisionBit;
                            terrainMap[wx][rawY] |= isStructureBit;
                            tilesWithStructure++;
                        }
                        terrainCounter += structure.structureObjectCount();
                    }
                }
                tilesClaimed++;
                claimedTiles.add(packTile(wx, wy));

                // colorlessBiome as the decode table: this tile's terrainMap was just written in
                // colorless index space above - see redrawMinimapTile()'s own comment for why
                // decoding it against the claiming color's differently-sized tables instead drew
                // wrong (or no) structure pixels, i.e. the flat-minimap symptom persisting.
                redrawMinimapTile(wx, rawY, colorlessBiome); // real content, not a flat stamp
                updateFogOfWarPixmap(wx, rawY);

                minX = Math.min(minX, wx); maxX = Math.max(maxX, wx);
                minY = Math.min(minY, wy); maxY = Math.max(maxY, wy);
            }
            }
        }
        // Repaint the live chunk textures only AFTER every tile's biomeMap/terrainMap write is
        // final - NOT per tile inside the loop above (the old behavior). generateBiomeSprite()
        // blends each tile against its 8 neighbors' current bits, so a mid-loop patch drew each
        // tile as if its not-yet-claimed east/south neighbors were still wasteland (edge pieces on
        // those sides), and a tile already patched never got re-patched when the loop then claimed
        // its neighbors - leaving the whole ring a grid of stale, hard-edged "islands" (reported:
        // "blocky/chunky creep... resolves when you walk over it" - walking re-patches the local
        // neighborhood with final state, which is exactly why it self-healed). Each claimed tile
        // AND its 8 neighbors get patched (deduped) - border tiles just OUTSIDE the claim also
        // re-blend, since their neighborhoods changed too.
        if (onTileRepainted != null && !claimedTiles.isEmpty()) {
            Set<Long> tilesToRepaint = new HashSet<>();
            for (long packed : claimedTiles) {
                int tx = (int) (packed >> 32);
                int ty = (int) packed;
                for (int nx = -1; nx <= 1; nx++)
                    for (int ny = -1; ny <= 1; ny++)
                        tilesToRepaint.add(packTile(tx + nx, ty + ny));
            }
            for (long packed : tilesToRepaint)
                onTileRepainted.accept((int) (packed >> 32), (int) packed);
        }
        if (tilesClaimed > 0) {
            System.out.println("[TerritoryControl] " + colorBiomeName + ": daily expansion claimed "
                    + tilesClaimed + " tile(s), " + tilesWithStructure + " with a structure");
            // Same reasoning as repaintBiomeAroundTown()'s own call: the per-tile minimap redraws
            // above can paint over a nearby POI's marker icon (markers are baked pixels, not
            // separate actors) - daily expansion sweeps across town markers as it grows, which
            // was clipping them out of the minimap a few pixels per day. Rect-scoped (2026-08-26
            // perf fix): only markers inside this ring's own repainted bounding box could have
            // been clipped, so only those get restored - the old full-map redraw here ran for
            // every one of 2000+ POIs, once per owner per day, and was the single largest
            // component of the reported day-end freeze. 5-tile margin: the widest marker (32px,
            // Capitol/castle) half-extends 16px from its POI's center, and at miniMapTileSize=4
            // one WORLD tile is 4 minimap pixels, so 16px = 4 tiles of reach - plus one spare.
            if (biomeImage != null && minX <= maxX)
                redrawPoiMarkers(minX - 5, minY - 5, maxX + 5, maxY + 5);
        }

        regenerateDoodadsInRadius(centerTileX, centerTileY, innerRadiusTiles, outerRadiusTiles, colorBiome, claimedTiles);

        if (onChunkNeedsReload != null && minX <= maxX) {
            int chunkSize = getChunkSize();
            int minChunkX = Math.floorDiv(minX, chunkSize);
            int maxChunkX = Math.floorDiv(maxX, chunkSize);
            int minChunkY = Math.floorDiv(minY, chunkSize);
            int maxChunkY = Math.floorDiv(maxY, chunkSize);
            for (int cx = minChunkX; cx <= maxChunkX; cx++)
                for (int cy = minChunkY; cy <= maxChunkY; cy++)
                    onChunkNeedsReload.accept(cx, cy);
        }
        if (outClaimedTiles != null)
            outClaimedTiles.addAll(claimedTiles);
        return tilesClaimed;
    }

    /**
     * Removes mapObjectIds doodad entries (rocks/flowers/etc, placed via BiomeData.spriteNames)
     * within the annulus between innerRadiusTiles and outerRadiusTiles, then re-places new ones
     * using the target biome's own spriteNames list. Simplified vs. the original world-gen
     * placement loop: density-only, no noise-region (startArea/endArea) gating - reasonable for a
     * small localized patch, not worth threading through the world-gen noise field for.
     * <p>
     * innerRadiusTiles exists for Territory Control's expansion mechanic (MOD_SCOPE.md #7): a
     * repeated, growing-radius claim needs to touch only the *new* ring each time, not re-clear
     * and re-randomize every doodad in the whole already-claimed interior on every tick (which
     * would visibly reshuffle settled territory's scenery every in-game day). Pass 0 for the
     * original single-circle behavior (repaintBiomeAroundTown()'s own use).
     *
     * BiomeSpriteData.density values (e.g. "Stone" at 0.01) are tuned for full world-gen, where
     * the map is thousands of tiles - over a radius-10 patch (~300 tiles) that same density only
     * yields ~3 doodads, easy to miss entirely. DOODAD_DENSITY_MULTIPLIER boosts density for just
     * this localized-repaint path so a recolored patch reads as visibly decorated, without
     * touching the shared density value world-gen itself still uses.
     */
    private static final float DOODAD_DENSITY_MULTIPLIER = 5f;

    // Packs a world tile coordinate into one long key for a Set<Long> membership test - x/y are
    // always small, non-negative map indices here, well within 32 bits each.
    private static long packTile(int x, int y) {
        return ((long) x << 32) | (y & 0xFFFFFFFFL);
    }

    private void regenerateDoodadsInRadius(int centerWorldX, int centerWorldY, int innerRadiusTiles, int outerRadiusTiles, BiomeData biome) {
        regenerateDoodadsInRadius(centerWorldX, centerWorldY, innerRadiusTiles, outerRadiusTiles, biome, null);
    }

    // claimedTiles, if non-null, restricts BOTH doodad removal and placement to exactly that set of
    // world tile coordinates instead of the full geometric annulus. Needed by claimWastelandRing():
    // its own ownership loop applies a nearest-anchor (Voronoi) check on top of the plain geometric
    // radius, which can reject a tile within outerRadiusTiles because a nearer rival (another AI
    // castle, or a player town) already owns it. Without this, this method used to remove/re-place
    // doodads across the FULL geometric ring regardless of who actually won each tile - stripping a
    // rival's legitimate doodads and placing this color's doodads on ground that never changed
    // color. See claimWastelandRing()'s own comment. repaintBiomeAroundTown() (the other caller, via
    // the null-forwarding overload above) has no Voronoi concept - a captured town unconditionally
    // owns its whole repaint disc - so it keeps the old geometric-only behavior unchanged.
    private void regenerateDoodadsInRadius(int centerWorldX, int centerWorldY, int innerRadiusTiles, int outerRadiusTiles, BiomeData biome, Set<Long> claimedTiles) {
        int innerRadiusSq = innerRadiusTiles * innerRadiusTiles;
        int outerRadiusSq = outerRadiusTiles * outerRadiusTiles;
        int tileSize = data.tileSize;
        int chunkSize = getChunkSize();
        int minChunkX = Math.floorDiv(centerWorldX - outerRadiusTiles, chunkSize);
        int maxChunkX = Math.floorDiv(centerWorldX + outerRadiusTiles, chunkSize);
        int minChunkY = Math.floorDiv(centerWorldY - outerRadiusTiles, chunkSize);
        int maxChunkY = Math.floorDiv(centerWorldY + outerRadiusTiles, chunkSize);

        for (int cx = minChunkX; cx <= maxChunkX; cx++) {
            for (int cy = minChunkY; cy <= maxChunkY; cy++) {
                List<Pair<Vector2, Integer>> objects = mapObjectIds.positions(cx, cy);
                objects.removeIf(entry -> {
                    int tx = (int) (entry.getLeft().x / tileSize);
                    int ty = (int) (entry.getLeft().y / tileSize);
                    if (claimedTiles != null)
                        return claimedTiles.contains(packTile(tx, ty));
                    int dx = tx - centerWorldX;
                    int dy = ty - centerWorldY;
                    int distSq = dx * dx + dy * dy;
                    return distSq <= outerRadiusSq && distSq >= innerRadiusSq;
                });
            }
        }

        if (biome.spriteNames == null)
            return;
        // Same road-preservation logic as repaintBiomeAroundTown()'s ground loop - a tile that
        // was skipped there (still the old biome/terrain because it's a road) shouldn't get a
        // fresh doodad placed on top of it either.
        long roadBit = 1L << data.GetBiomes().size();
        for (int wx = centerWorldX - outerRadiusTiles; wx <= centerWorldX + outerRadiusTiles; wx++) {
            if (wx < 0 || wx >= width)
                continue;
            int dx = wx - centerWorldX;
            for (int wy = centerWorldY - outerRadiusTiles; wy <= centerWorldY + outerRadiusTiles; wy++) {
                if (wy < 0 || wy >= height)
                    continue;
                int dy = wy - centerWorldY;
                int distSq = dx * dx + dy * dy;
                if (distSq > outerRadiusSq || distSq < innerRadiusSq || isStructure(wx, wy))
                    continue;
                if (claimedTiles != null && !claimedTiles.contains(packTile(wx, wy)))
                    continue;
                if ((biomeMap[wx][height - wy - 1] & roadBit) != 0)
                    continue;
                for (String name : biome.spriteNames) {
                    BiomeSpriteData sprite = data.GetBiomeSprites().getSpriteData(name);
                    if (sprite == null || random.nextFloat() > Math.min(1f, sprite.density * DOODAD_DENSITY_MULTIPLIER))
                        continue;
                    String spriteKey = sprite.key();
                    int key = mapObjectIds.containsKey(spriteKey)
                            ? mapObjectIds.intKey(spriteKey)
                            : mapObjectIds.put(spriteKey, sprite, data.GetBiomeSprites());
                    mapObjectIds.putPosition(key, new Vector2(
                            (wx + .25f + random.nextFloat() / 2) * tileSize,
                            (wy + .25f - random.nextFloat() / 2) * tileSize));
                    break; // one doodad per tile, same as original world-gen placement
                }
            }
        }
    }

    // Companion to neutralizeTerritoryOutsideRadius() - that method reskins structures via
    // translateStructure() but, like this one used to, never touches mapObjectIds (rocks/flowers/
    // etc), so a color's own original doodads were left sitting untouched on now-wasteland ground
    // even after every structure nearby was correctly reskinned - part of why the swept area
    // didn't read as "one continuous area" with wasteland's own core territory. Full-map scan
    // (like neutralizeTerritoryOutsideRadius() itself) rather than a bounded radius, since "every
    // tile this biome currently owns" has no single center/radius after 5 colors' sweeps have all
    // run - acceptable for the same reason that method's own full-map scan is: a one-time
    // post-generation pass, not per-frame or per-capture. Uses the biome's own natural density (no
    // DOODAD_DENSITY_MULTIPLIER boost - that's calibrated for a small, otherwise-sparse localized
    // patch, not appropriate at map scale here).
    public void regenerateDoodadsForBiome(String biomeName) {
        if (data == null || biomeMap == null || mapObjectIds == null)
            return;
        List<BiomeData> biomes = data.GetBiomes();
        int biomeIndex = -1;
        for (int i = 0; i < biomes.size(); i++) {
            if (biomeName.equalsIgnoreCase(biomes.get(i).name)) {
                biomeIndex = i;
                break;
            }
        }
        if (biomeIndex < 0)
            return;
        BiomeData biome = biomes.get(biomeIndex);
        if (biome.spriteNames == null)
            return;

        int tileSize = data.tileSize;
        final int targetBiomeIndex = biomeIndex;
        for (int cx = 0; cx < getWidthInChunks(); cx++) {
            for (int cy = 0; cy < getHeightInChunks(); cy++) {
                List<Pair<Vector2, Integer>> objects = mapObjectIds.positions(cx, cy);
                objects.removeIf(entry -> {
                    int tx = (int) (entry.getLeft().x / tileSize);
                    int ty = (int) (entry.getLeft().y / tileSize);
                    return highestBiome(getBiome(tx, ty)) == targetBiomeIndex;
                });
            }
        }

        long roadBit = 1L << biomes.size();
        for (int wx = 0; wx < width; wx++) {
            for (int wy = 0; wy < height; wy++) {
                if (highestBiome(getBiome(wx, wy)) != biomeIndex || isStructure(wx, wy))
                    continue;
                if ((biomeMap[wx][height - wy - 1] & roadBit) != 0)
                    continue;
                for (String name : biome.spriteNames) {
                    BiomeSpriteData sprite = data.GetBiomeSprites().getSpriteData(name);
                    if (sprite == null || random.nextFloat() > sprite.density)
                        continue;
                    String spriteKey = sprite.key();
                    int key = mapObjectIds.containsKey(spriteKey)
                            ? mapObjectIds.intKey(spriteKey)
                            : mapObjectIds.put(spriteKey, sprite, data.GetBiomeSprites());
                    mapObjectIds.putPosition(key, new Vector2(
                            (wx + .25f + random.nextFloat() / 2) * tileSize,
                            (wy + .25f - random.nextFloat() / 2) * tileSize));
                    break;
                }
            }
        }
    }

    /**
     * Marks tiles within radius of (centerWorldX, centerWorldY) as explored (circular area, tile
     * coordinates in the same world-space as getBiome()/getBiomeSprite()). For each tile that was
     * not already explored, updates the minimap fog pixmap and invokes onTileRevealed(x, y) so
     * callers (e.g. WorldBackground) can patch any already-built ground textures in place.
     */
    public void revealArea(int centerWorldX, int centerWorldY, int radius, BiConsumer<Integer, Integer> onTileRevealed) {
        if (!isFogOfWarEnabled() || explored == null) return;
        int minX = Math.max(0, centerWorldX - radius);
        int maxX = Math.min(width - 1, centerWorldX + radius);
        int minY = Math.max(0, centerWorldY - radius);
        int maxY = Math.min(height - 1, centerWorldY + radius);
        int radiusSq = radius * radius;
        for (int wx = minX; wx <= maxX; wx++) {
            int dx = wx - centerWorldX;
            for (int wy = minY; wy <= maxY; wy++) {
                int dy = wy - centerWorldY;
                if (dx * dx + dy * dy > radiusSq)
                    continue;
                int rawY = height - wy - 1;
                if (rawY < 0 || rawY >= height || explored[wx][rawY])
                    continue;
                explored[wx][rawY] = true;
                updateFogOfWarPixmap(wx, rawY);
                if (onTileRevealed != null)
                    onTileRevealed.accept(wx, wy);
            }
        }
    }

    /**
     * FoW Stage 2 (2026-08-11 user spec): "if 80% of the world map is discovered, reveal the
     * entire map." Called once per in-game day (WorldStage's daily tick, alongside Territory
     * Control/Dungeon Rotation's own once-a-day checks) rather than every frame - a full
     * width*height scan is cheap at that cadence, not at 60fps. onTileRevealed lets the caller
     * (WorldStage.refreshBackgroundTile) patch any already-built ground chunks in place, same
     * bridge revealArea() already uses - deliberately NOT routed through temporarilyReveal()'s
     * discovery-flash layer (#3), since a whole-map flash reads as noise, not a moment worth
     * calling out; tiles just settle straight into their ordinary known/dimmed tier.
     */
    // Lazily-cached land-tile denominator for checkFogOfWarStage2 (2026-08-13 fix) - see below.
    private transient int cachedLandTileTotal = -1;

    /** True for tiles that are actual explorable land - anything beyond the pure base/ocean
     *  biome (index 0 by world.json convention: base, colorless, the 5 colors, then player -
     *  the same ordering claimWastelandRing()'s own untouchable-tile classification relies on). */
    private boolean isLandTile(int x, int rawY) {
        return biomeMap != null && highestBiome(biomeMap[x][rawY]) != 0;
    }

    public void checkFogOfWarStage2(BiConsumer<Integer, Integer> onTileRevealed) {
        if (!isFogOfWarEnabled() || explored == null || fogOfWarStage2Revealed)
            return;
        // 2026-08-13 fix (user report: "the map had revealed itself... but I don't think it was
        // accurate"): the 80% used to be measured over ALL width*height tiles, ocean included.
        // Deep ocean can never be walked and (post-fix) is never revealed by territory growth, so
        // the old denominator both let a huge Capitol reveal disc trip the threshold while the
        // visible landmass still looked mostly dark, AND would have made the message unreachable
        // once territory reveals stopped covering ocean. Measured over land tiles only now, which
        // matches the intuitive meaning of "80% of the map".
        if (cachedLandTileTotal < 0) {
            int landCount = 0;
            for (int x = 0; x < width; x++)
                for (int rawY = 0; rawY < height; rawY++)
                    if (isLandTile(x, rawY))
                        landCount++;
            cachedLandTileTotal = landCount;
        }
        if (cachedLandTileTotal <= 0)
            return;
        int exploredLand = 0;
        for (int x = 0; x < width; x++)
            for (int rawY = 0; rawY < height; rawY++)
                if (explored[x][rawY] && isLandTile(x, rawY))
                    exploredLand++;
        double pct = exploredLand / (double) cachedLandTileTotal;
        // Diagnostic logging standard - one line per daily evaluation so the threshold's actual
        // inputs are verifiable from forge.log (this whole mechanic previously never logged
        // itself; the 2026-08-13 early-fire bug had to be reconstructed from territory-radius
        // lines alone).
        System.out.println("[TFR-FoW] stage2 check: exploredLand=" + exploredLand + "/" + cachedLandTileTotal
                + " (" + String.format(java.util.Locale.ROOT, "%.1f", pct * 100) + "%, threshold 80%)");
        if (pct < 0.80)
            return;
        System.out.println("[TFR-FoW] stage2 FIRED - revealing the full map");
        fogOfWarStage2Revealed = true;
        for (int x = 0; x < width; x++) {
            for (int rawY = 0; rawY < height; rawY++) {
                if (explored[x][rawY])
                    continue;
                explored[x][rawY] = true;
                if (onTileRevealed != null)
                    onTileRevealed.accept(x, height - rawY - 1); // rawY -> world Y, inverse of revealArea()'s rawY math
            }
        }
        rebuildFogOfWarPixmap();
        // authoredMarkup=true, or the tint-BLACK default multiplies the [CYAN] tag away (same
        // rule GameHUD.addNotification documents; found 2026-08-12 while confirming Stage 2).
        GameHUD.getInstance().addNotification("[CYAN]Enough of the realm is known that its full shape reveals itself to you - the map is now fully explored.", true);
    }

    public boolean isExploredWorld(int x, int y) {
        if (!isFogOfWarEnabled() || explored == null)
            return true;
        try {
            return explored[x][height - y - 1];
        } catch (ArrayIndexOutOfBoundsException e) {
            return false;
        }
    }

    /**
     * Reveals only the tiles the player actually OWNS (player biome bit set) within the given
     * radius of a center - the ownership-accurate replacement (2026-08-13) for the load-time
     * Capitol sweep that used to reveal the whole geometric territory-radius disc, ocean and
     * rival land included (a major contributor to the fully-explored-fired-too-early bug).
     * Returns how many tiles were newly revealed, for the [TFR-FoW] log at the call site.
     */
    public int revealPlayerOwnedTiles(int centerWorldX, int centerWorldY, int radius,
                                       BiConsumer<Integer, Integer> onTileRevealed) {
        if (!isFogOfWarEnabled() || explored == null || biomeMap == null)
            return 0;
        long playerBit = playerBiomeBit();
        if (playerBit == 0)
            return 0;
        int revealed = 0;
        int minX = Math.max(0, centerWorldX - radius);
        int maxX = Math.min(width - 1, centerWorldX + radius);
        int minY = Math.max(0, centerWorldY - radius);
        int maxY = Math.min(height - 1, centerWorldY + radius);
        int radiusSq = radius * radius;
        for (int wx = minX; wx <= maxX; wx++) {
            int dx = wx - centerWorldX;
            for (int wy = minY; wy <= maxY; wy++) {
                int dy = wy - centerWorldY;
                if (dx * dx + dy * dy > radiusSq)
                    continue;
                int rawY = height - wy - 1;
                if (rawY < 0 || rawY >= height || explored[wx][rawY])
                    continue;
                if ((biomeMap[wx][rawY] & playerBit) == 0)
                    continue;
                explored[wx][rawY] = true;
                updateFogOfWarPixmap(wx, rawY);
                revealed++;
                if (onTileRevealed != null)
                    onTileRevealed.accept(wx, wy);
            }
        }
        return revealed;
    }

    /**
     * One-shot repair for a save whose explored[][] was over-inflated by the pre-2026-08-13
     * radius-disc reveals (console command "fog reset"): rebuilds explored from scratch as
     * player-owned ground plus every owned town's vision circle, and re-arms the Stage-2
     * fully-explored trigger. Deliberately opt-in - walked-exploration history is not separable
     * from the over-reveal, so this also forgets legitimately walked ground.
     */
    public String resetFogOfWarToOwnership() {
        if (!isFogOfWarEnabled() || explored == null || biomeMap == null)
            return "Fog of war is not enabled on this plane.";
        rebuildPlayerTownVision();
        long playerBit = playerBiomeBit();
        int revealed = 0;
        for (int x = 0; x < width; x++) {
            for (int rawY = 0; rawY < height; rawY++) {
                int wy = height - rawY - 1;
                boolean keep = playerBit != 0 && (biomeMap[x][rawY] & playerBit) != 0;
                if (!keep) {
                    for (int[] area : playerTownVisionAreas) {
                        int dx = x - area[0];
                        int dy = wy - area[1];
                        if (dx * dx + dy * dy <= area[2]) {
                            keep = true;
                            break;
                        }
                    }
                }
                explored[x][rawY] = keep;
                if (keep)
                    revealed++;
            }
        }
        fogOfWarStage2Revealed = false;
        rebuildFogOfWarPixmap();
        System.out.println("[TFR-FoW] reset-to-ownership: " + revealed + " tiles revealed, stage2 flag re-armed");
        return "Fog rebuilt from ownership: " + revealed + " tiles revealed, full-map trigger re-armed. "
                + "Save and reload to fully refresh the rendered map.";
    }

    // Day/night cycle: opt-in per-plane via config.json ("dayNightCycleEnabled": true), defaulting
    // to off so this doesn't affect Shandalar or any other existing plane. advanceTime() is only
    // ever called by WorldStage.onActing(), so the clock naturally freezes in towns/dungeons
    // (MapStage) and while the game is paused or showing a dialog.
    public void advanceTime(float delta) {
        if (!isDayNightCycleEnabled())
            return;
        dayProgress += delta / dayLengthSeconds();
        while (dayProgress >= 1f) {
            dayProgress -= 1f;
            dayCount++;
        }
    }

    public boolean isDayNightCycleEnabled() {
        ConfigData configData = Config.instance().getConfigData();
        return configData != null && configData.dayNightCycleEnabled;
    }

    public boolean isTerritoryControlEnabled() {
        ConfigData configData = Config.instance().getConfigData();
        return configData != null && configData.territoryControlEnabled;
    }

    public boolean isEditionProgressionEnabled() {
        ConfigData configData = Config.instance().getConfigData();
        return configData != null && configData.editionProgressionEnabled;
    }

    public boolean isFunctioningNeutralTownsEnabled() {
        ConfigData configData = Config.instance().getConfigData();
        return configData != null && configData.functioningNeutralTownsEnabled;
    }

    /** Fraction of the current day elapsed, in [0,1), where 0 is midnight. */
    public float getDayProgress() {
        return dayProgress;
    }

    public float getHourOfDay() {
        return dayProgress * 24f;
    }

    public int getCurrentDay() {
        return dayCount;
    }

    public boolean isNight() {
        float hour = getHourOfDay();
        return hour >= NIGHT_START_HOUR || hour < NIGHT_END_HOUR;
    }

    /**
     * Day/night terrain life modifier (user spec 2026-08-12; MOD_SCOPE.md #6's long-planned
     * "monsters buffed by day or night", and the first real consumer of isNight()). OVERWORLD
     * fights only - the DuelScene call site excludes Arena/Inn events and town/dungeon
     * interiors. By the CURRENT terrain color under the fight (same tile-ownership lookup the
     * re-theme/roaming spawner uses, so captured land counts as its new owner):
     * White +10% day / -10% night; Green +5% / -5%; Black -10% day / +10% night; Red -5% / +5%;
     * Blue, neutral/wasteland, and player terrain unaffected. The delta is ceil() of the
     * percentage ("rounded up" per spec, applied to both directions), floored at 1 life.
     */
    public int applyDayNightTerrainLife(int baseLife, int tileX, int tileY) {
        if (!isDayNightCycleEnabled() || baseLife <= 0)
            return baseLife;
        int biomeIndex = highestBiome(getBiome(tileX, tileY));
        java.util.List<BiomeData> biomes = data == null ? null : data.GetBiomes();
        if (biomes == null || biomeIndex < 0 || biomeIndex >= biomes.size())
            return baseLife;
        String terrain = biomes.get(biomeIndex).name;
        int dayPct;
        switch (terrain == null ? "" : terrain) {
            case "white": dayPct = 10; break;
            case "green": dayPct = 5; break;
            case "black": dayPct = -10; break;
            case "red": dayPct = -5; break;
            default: return baseLife;
        }
        int pct = isNight() ? -dayPct : dayPct;
        int delta = (int) Math.ceil(baseLife * Math.abs(pct) / 100f);
        int result = pct > 0 ? baseLife + delta : Math.max(1, baseLife - delta);
        // Diagnostic-only logging - greppable in forge.log as "[TFR-DayNight]".
        System.out.println("[TFR-DayNight] " + terrain + " terrain, " + (isNight() ? "night" : "day")
                + ": enemy life " + baseLife + " -> " + result);
        return result;
    }

    // FoW player vision radius scales with difficulty (2026-08-11 user spec): `visionRadius`
    // itself stays the Normal/Hard baseline (still the item-upgradeable value #3's own comment
    // refers to) with a difficulty offset applied on top - Easy sees one tile further, Insane one
    // tile less, the two middle tiers unchanged. Deliberately not the linear per-step scale
    // TerritoryControl.maxActiveMagesPerColor() uses elsewhere - this one ties Normal/Hard
    // together on purpose, first/last tier treated as Easy/Insane regardless of how many
    // difficulties are actually configured.
    public int getVisionRadius() {
        // Torch (2026-08-13): the item multiplier applies to the difficulty-adjusted radius, not
        // just the bare baseline - "3x current radius" per spec, and this is the exact extension
        // point the class comment above already anticipated ("items will raise this later").
        return Math.round((visionRadius + visionRadiusDifficultyOffset()) * Current.player().visionRadiusMultiplier());
    }

    private int visionRadiusDifficultyOffset() {
        DifficultyData playerDifficulty = Current.player().getDifficulty();
        DifficultyData[] allDifficulties = Config.instance().getConfigData().difficulties;
        if (playerDifficulty == null || playerDifficulty.name == null || allDifficulties == null || allDifficulties.length == 0)
            return 0;
        for (int i = 0; i < allDifficulties.length; i++) {
            if (playerDifficulty.name.equals(allDifficulties[i].name)) {
                if (i == 0)
                    return 1;
                if (i == allDifficulties.length - 1)
                    return -1;
                return 0;
            }
        }
        return 0;
    }

    public void setVisionRadius(int visionRadius) {
        this.visionRadius = visionRadius;
    }

    // Two-tier fog: "known" (explored[][], persisted forever once seen - see isExploredWorld())
    // vs "currently visible" (real-time, live vision radius around the player's current position,
    // NOT persisted - recomputed every frame from these two fields). Known-but-not-currently-visible
    // tiles render hazed (see hazeTile()) rather than fully hidden or fully bright: you remember the
    // terrain shape, but not what's happening there right now (a monster that's since wandered
    // through, etc). Set once per frame by WorldBackground.draw(), which already knows the player's
    // current tile position for the reveal-on-move logic.
    private int visiblePlayerTileX = Integer.MIN_VALUE;
    private int visiblePlayerTileY = Integer.MIN_VALUE;
    // Difficulty-scaled radius, cached because isCurrentlyVisible() runs per TILE during chunk
    // builds (up to several thousand tiles in one frame at a chunk seam) and per enemy per frame
    // - getVisionRadius() walks the config's difficulty list with string compares on every call,
    // far too hot for those paths (2026-08-12 review finding). Refreshed here once per frame
    // alongside the position; difficulty can't change mid-session, so staleness isn't possible
    // beyond the first frame, and the <0 sentinel covers any pre-first-frame call.
    private int cachedVisionRadius = -1;

    public void setPlayerTilePosition(int tileX, int tileY) {
        visiblePlayerTileX = tileX;
        visiblePlayerTileY = tileY;
        cachedVisionRadius = getVisionRadius();
    }

    // Fog of war, third tier (per user spec 2026-08-08): the area around every PLAYER-OWNED town
    // counts as REVEALED - fully visible, not just explored/hazed - at that town's current
    // territory radius (townTerritoryRadius, RECOLOR_RADIUS at capture, growing to
    // TOWN_MAX_TERRITORY_RADIUS), "same radius as first captured... this will also expand as the
    // town grows its borders." Cached as {tileX, tileY, radiusSq} triples rather than re-derived
    // per call - isCurrentlyVisible() runs per tile during chunk builds/haze patching, and
    // scanning every POI + save-flag there would be far too hot. Rebuilt only when ownership or a
    // radius can actually have changed: save load, a town restore, an AI capture, and the daily
    // expansion tick.
    private final List<int[]> playerTownVisionAreas = new ArrayList<>();

    public void rebuildPlayerTownVision() {
        playerTownVisionAreas.clear();
        if (data == null)
            return;
        for (PointOfInterest poi : getAllPointOfInterest()) {
            forge.adventure.pointofintrest.PointOfInterestChanges changes =
                    WorldSave.getCurrentSave().peekPointOfInterestChanges(poi.getID());
            if (!TownRestoration.isTownRestored(changes))
                continue;
            playerTownVisionAreas.add(new int[]{
                    (int) (poi.getPosition().x / data.tileSize),
                    (int) (poi.getPosition().y / data.tileSize),
                    getTownVisionRadiusTiles(poi, changes)});
        }
        // Squared once, after the fact, so getTownVisionRadiusTiles() can stay in plain tiles for
        // its other callers (EconomyBuildings' Outlook build/destroy refresh).
        for (int[] area : playerTownVisionAreas)
            area[2] = area[2] * area[2];
    }

    /**
     * A player-owned town's fog-of-war vision radius in TILES. The Capitol's is deliberately its
     * castle keep radius, NOT its mirrored territory radius (2026-08-09 FoW repair): the mirror
     * grows toward MAX_TERRITORY_RADIUS (450) with daily expansion, and using it here made a huge
     * disc count as permanently Revealed regardless of what the player actually held or had even
     * explored - which is what collapsed fog of war to two visible states (everything near the
     * player Revealed-bright, everything else unexplored-black, no hazed middle tier anywhere)
     * and let mage minimap dots show over unexplored ground inside the circle (both
     * user-reported). Actual held GROUND is Revealed via the ownership-bit check in
     * isPersistentlyRevealed() instead - exact, not a circle. Outlook (vision building): x2 for a
     * town, x3 for the Capitol (user spec 2026-08-09).
     */
    public int getTownVisionRadiusTiles(PointOfInterest poi, forge.adventure.pointofintrest.PointOfInterestChanges changes) {
        boolean isCapitol = TownRestoration.CAPITOL_POI_NAME.equals(poi.getData().name);
        int r;
        if (isCapitol) {
            r = TerritoryControl.CASTLE_KEEP_RADIUS_TILES;
        } else {
            Integer radius = townTerritoryRadius.get(poi.getID());
            r = radius != null ? radius : TerritoryControl.RECOLOR_RADIUS;
        }
        if (changes != null && changes.hasEconomyBuildingOfType(EconomyBuildings.OUTLOOK))
            r *= isCapitol ? 3 : 2;
        return r;
    }

    // Lazily-resolved "player" biome bit for isPersistentlyRevealed()'s ownership check; -2 =
    // not looked up yet, -1 = this plane has no player biome (every stock plane).
    private int playerBiomeIndexCache = -2;

    private long playerBiomeBit() {
        if (playerBiomeIndexCache == -2) {
            playerBiomeIndexCache = -1;
            List<BiomeData> biomes = data.GetBiomes();
            for (int i = 0; i < biomes.size(); i++) {
                if ("player".equalsIgnoreCase(biomes.get(i).name)) {
                    playerBiomeIndexCache = i;
                    break;
                }
            }
        }
        return playerBiomeIndexCache < 0 ? 0L : 1L << playerBiomeIndexCache;
    }

    /**
     * The PERSISTENT (player-position-independent) Revealed tier: ground the player actually owns
     * (the player biome bit painted by captures/expansion - exact per tile, not a circle), plus
     * each owned town's vision circle (which Outlook widens). Split out from isCurrentlyVisible()
     * so the minimap fog overlay can use exactly this tier without the player's transient vision
     * circle (the overlay only re-snapshots per day/enter, so a baked-in transient circle would
     * just go stale and smear).
     */
    public boolean isPersistentlyRevealed(int x, int y) {
        long playerBit = playerBiomeBit();
        if (playerBit != 0 && biomeMap != null && x >= 0 && x < width && y >= 0 && y < height) {
            int rawY = height - y - 1;
            if (rawY >= 0 && rawY < height && (biomeMap[x][rawY] & playerBit) != 0)
                return true;
        }
        for (int[] area : playerTownVisionAreas) {
            int tx = x - area[0];
            int ty = y - area[1];
            if (tx * tx + ty * ty <= area[2])
                return true;
        }
        return false;
    }

    public boolean isCurrentlyVisible(int x, int y) {
        if (!isFogOfWarEnabled())
            return true;
        int dx = x - visiblePlayerTileX;
        int dy = y - visiblePlayerTileY;
        int radius = cachedVisionRadius >= 0 ? cachedVisionRadius : getVisionRadius();
        if (dx * dx + dy * dy <= radius * radius)
            return true;
        if (isTemporarilyRevealed(x, y))
            return true;
        return isPersistentlyRevealed(x, y);
    }

    // Discovery flash (2026-08-10, user spec): "when you first get close to a town, or enemy
    // capital, the FoW should clear briefly, then go to the middle state". The player's own live
    // vision circle already does this for tiles close enough to stand near, but
    // WorldBackground's wider DISCOVERY_REVEAL_RADIUS burst around a newly-found POI marks its
    // OUTER ring explored (dimmed-tier forever after) without ever passing through a bright
    // moment first, since those tiles are typically outside the live vision circle. This is a
    // separate, TIME-LIMITED tier bolted onto isCurrentlyVisible() above - a tile flashes bright
    // for TEMPORARY_REVEAL_SECONDS, then falls through to its ordinary tier (dimmed once
    // explored, unless something else keeps it persistently bright).
    private final java.util.Map<Long, Float> temporaryRevealTimers = new java.util.HashMap<>();
    private static final float TEMPORARY_REVEAL_SECONDS = 3f;

    private static long packWorldTile(int x, int y) {
        return ((long) x << 32) | (y & 0xffffffffL);
    }

    /** Starts (or refreshes) a discovery flash on this tile - see the class comment above. */
    public void temporarilyReveal(int wx, int wy) {
        temporaryRevealTimers.put(packWorldTile(wx, wy), TEMPORARY_REVEAL_SECONDS);
    }

    public boolean isTemporarilyRevealed(int wx, int wy) {
        // Called per tile from isCurrentlyVisible() during chunk builds - the isEmpty() check
        // skips the Long autoboxing + map lookup in the near-permanent no-flash-active state
        // (same fast path tickTemporaryReveals() already had).
        if (temporaryRevealTimers.isEmpty())
            return false;
        Float remaining = temporaryRevealTimers.get(packWorldTile(wx, wy));
        return remaining != null && remaining > 0f;
    }

    /**
     * Ticks every active discovery flash down by delta, repainting any tile whose flash JUST
     * expired back to its ordinary tier (it was drawn bright while flashing - without this it
     * would stay looking bright forever once the timer silently hit zero). Cheap no-op whenever
     * nothing is currently flashing, which is nearly always true.
     */
    public void tickTemporaryReveals(float delta, BiConsumer<Integer, Integer> onTileChanged) {
        if (temporaryRevealTimers.isEmpty())
            return;
        java.util.Iterator<java.util.Map.Entry<Long, Float>> it = temporaryRevealTimers.entrySet().iterator();
        while (it.hasNext()) {
            java.util.Map.Entry<Long, Float> entry = it.next();
            float remaining = entry.getValue() - delta;
            if (remaining <= 0f) {
                it.remove();
                long key = entry.getKey();
                int wx = (int) (key >> 32);
                int wy = (int) (key & 0xffffffffL);
                if (onTileChanged != null)
                    onTileChanged.accept(wx, wy);
            } else {
                entry.setValue(remaining);
            }
        }
    }

    private Pixmap getFogTile() {
        if (fogTilePixmap == null) {
            fogTilePixmap = new Pixmap(data.tileSize, data.tileSize, Pixmap.Format.RGBA8888);
            fogTilePixmap.setColor(0, 0, 0, 1);
            fogTilePixmap.fill();
        }
        return fogTilePixmap;
    }

    // Returns a darkened COPY of the given tile - never mutates it in place, since some callers of
    // getBiomeSprite() (the edge-of-map case in generateBiomeSprite()) return a shared/cached Pixmap
    // reused across many tile lookups, not a fresh one, and tinting it in place would corrupt every
    // other tile that shares it.
    private Pixmap hazeTile(Pixmap real) {
        Pixmap haze = new Pixmap(real.getWidth(), real.getHeight(), Pixmap.Format.RGBA8888);
        haze.setBlending(Pixmap.Blending.None);
        haze.drawPixmap(real, 0, 0);
        haze.setBlending(Pixmap.Blending.SourceOver);
        // Neutral black, no color bias - was (0,0,0.05,0.55), a slight blue tint that reads as a
        // "border" wherever the player walks away from an area (known-but-not-currently-visible
        // tiles trailing the vision-radius circle), reported as a weird blue border effect.
        haze.setColor(0f, 0f, 0f, 0.55f);
        haze.fillRectangle(0, 0, haze.getWidth(), haze.getHeight());
        return haze;
    }

    // rawX/rawY are in biomeMap's raw/image-space (unflipped), matching the x,y loop that built biomeImage.
    // THREE tiers now (2026-08-09, user-requested - was two): unknown (solid black), explored
    // (dimmed veil), and persistently Revealed (full brightness - owned ground and owned-town
    // vision circles). The transient vision circle around the player deliberately does NOT get
    // the bright tier here: the minimap texture only re-snapshots per in-game day (or scene
    // enter), so baking the player's momentary circle in would leave a stale bright smear
    // wherever they happened to be standing at snapshot time.
    private void updateFogOfWarPixmap(int rawX, int rawY) {
        if (fogOfWarPixmap == null || biomeImage == null || data == null)
            return;
        int mm = data.miniMapTileSize;
        // UNEXPLORED tiles stay solid black - this method used to unconditionally paint the hazed
        // "discovered" look (biome copy + translucent veil), which was correct for its original
        // sole caller (revealArea(), which marks a tile explored first) but wrong for Territory
        // Control's repaint paths, which call this for EVERY tile they touch: a fresh fog-of-war
        // game showed the AI castles' areas and every day's expansion creep on the minimap as if
        // the player had discovered them (real, reported bug).
        if (explored == null || !explored[rawX][rawY]) {
            fogOfWarPixmap.setBlending(Pixmap.Blending.None);
            fogOfWarPixmap.setColor(0, 0, 0, 1);
            fogOfWarPixmap.fillRectangle(rawX * mm, rawY * mm, mm, mm);
            fogOfWarPixmap.setBlending(Pixmap.Blending.SourceOver);
            return;
        }
        fogOfWarPixmap.setBlending(Pixmap.Blending.None);
        fogOfWarPixmap.drawPixmap(biomeImage, rawX * mm, rawY * mm, mm, mm, rawX * mm, rawY * mm, mm, mm);
        fogOfWarPixmap.setBlending(Pixmap.Blending.SourceOver);
        if (!isPersistentlyRevealed(rawX, height - rawY - 1)) {
            fogOfWarPixmap.setColor(0f, 0f, 0.05f, 0.5f);
            fogOfWarPixmap.fillRectangle(rawX * mm, rawY * mm, mm, mm);
        }
    }

    /**
     * Re-derives the fog overlay AND the baked ground textures for every tile within radius of a
     * center tile - for events that change a whole area's REVEALED state without touching
     * explored[][] at all (Outlook built/destroyed, a town gained/lost). revealArea() can't do
     * this: it early-outs on already-explored tiles, which are exactly the ones whose tier
     * changed. Call rebuildPlayerTownVision() first - both the fog overlay and the re-baked
     * ground read the vision cache.
     */
    public void refreshFogInRadius(int centerWorldX, int centerWorldY, int radius, BiConsumer<Integer, Integer> onTileRepainted) {
        if (!isFogOfWarEnabled() || data == null)
            return;
        int radiusSq = radius * radius;
        for (int wx = Math.max(0, centerWorldX - radius); wx <= Math.min(width - 1, centerWorldX + radius); wx++) {
            int dx = wx - centerWorldX;
            for (int wy = Math.max(0, centerWorldY - radius); wy <= Math.min(height - 1, centerWorldY + radius); wy++) {
                int dy = wy - centerWorldY;
                if (dx * dx + dy * dy > radiusSq)
                    continue;
                updateFogOfWarPixmap(wx, height - wy - 1);
                if (onTileRepainted != null)
                    onTileRepainted.accept(wx, wy);
            }
        }
    }

    /** Rebuilds the minimap's fog overlay from the current explored[][] state. Only needed after
     *  toggling fog of war on mid-session (e.g. the debug HUD toggle) - normal reveals patch the
     *  pixmap incrementally via updateFogOfWarPixmap() instead of a full rebuild. */
    public void rebuildFogOfWarPixmap() {
        if (!isFogOfWarEnabled() || biomeImage == null || explored == null)
            return;
        if (fogOfWarPixmap != null)
            fogOfWarPixmap.dispose();
        fogOfWarPixmap = new Pixmap(biomeImage.getWidth(), biomeImage.getHeight(), Pixmap.Format.RGBA8888);
        fogOfWarPixmap.setColor(0, 0, 0, 1);
        fogOfWarPixmap.fill();
        int mm = data.miniMapTileSize;
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                if (explored[x][y]) {
                    updateFogOfWarPixmap(x, y);
                }
            }
        }
    }

    public List<Pair<Vector2, Integer>> GetMapObjects(int chunkX, int chunkY) {
        return mapObjectIds.positions(chunkX, chunkY);
    }

    public List<PointOfInterest> getPointsOfInterest(Actor player) {
        return mapPoiIds.pointsOfInterest((int) player.getX() / data.tileSize / getChunkSize(), (int) player.getY() / data.tileSize / getChunkSize());
    }

    public List<PointOfInterest> getPointsOfInterest(int chunkX, int chunkY) {
        return mapPoiIds.pointsOfInterest(chunkX, chunkY);
    }

    public PointOfInterest findPointsOfInterest(String name) {
        return mapPoiIds.findPointsOfInterest(name);
    }

    public List<PointOfInterest> getAllPointOfInterest(){
        // mapPoiIds is only populated by generateNew()/load() - null here means no world exists
        // yet (confirmed via forge.log: GameHUD's singleton, and now TownCountActor inside it, is
        // constructed once as part of opening Adventure mode itself, before the player has picked
        // New Game/Continue/Load - not just lazily on first real gameplay frame as assumed).
        // Empty list is the correct "no towns yet" answer, not a crash.
        return mapPoiIds == null ? new ArrayList<>() : mapPoiIds.getAllPointOfInterest();
    }

    public int getChunkSize() {
        return (Math.max(Scene.getIntendedWidth(), Scene.getIntendedHeight())) / data.tileSize;
    }

    public void dispose() {

        if (biomeImage != null) biomeImage.dispose();
        if (fogOfWarPixmap != null) fogOfWarPixmap.dispose();
        if (fogTilePixmap != null) fogTilePixmap.dispose();
    }

    public void setSeed(long seedOffset) {
        random.setSeed(seedOffset + seed);
    }

    public Texture getGlobalTexture() {
        if (globalTexture == null) {
            globalTexture = Forge.getAssets().getTexture(Config.instance().getFile("ui/sprite_markers.png"), true, true);
            System.out.print("Loading auxiliary sprites.\n");
        }
        return globalTexture;
    }
}
