package forge.adventure.data;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.utils.Array;

import forge.adventure.util.AdventureQuestController;
import forge.adventure.util.SpawnTierWeighting;
import forge.adventure.world.World;
import forge.adventure.world.WorldSave;
import forge.util.Aggregates;
import forge.util.MyRandom;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Data class that will be used to read Json configuration files
 * BiomeData
 * contains the information for the biomes
 */
public class BiomeData implements Serializable {
    // Save compatibility: pinned 2026-09-03 (round 90) at the value derived from the v1.04 class shape so save compatibility no longer depends on the class not changing.
    private static final long serialVersionUID = -1638899071017310121L;

    public float startPointX;
    public float startPointY;
    public float noiseWeight;
    public float distWeight;
    public String name;
    public String tilesetAtlas;
    public String tilesetName;
    public BiomeTerrainData[] terrain;
    public float width;
    public float height;
    public String color;
    public boolean collision;
    public boolean invertHeight;
    public String[] spriteNames;
    public String[] enemies;
    public String[] pointsOfInterest;
    public BiomeStructureData[] structures;

    private ArrayList<EnemyData> enemyList;
    private ArrayList<PointOfInterestData> pointOfInterestList;

    private final Random rand = MyRandom.getRandom();

    public Color GetColor() {
        return Color.valueOf(color);
    }

    public ArrayList<EnemyData> getEnemyList() {
        if (enemyList == null) {
            enemyList = new ArrayList<>();
            if (enemies == null)
                return enemyList;
            for (EnemyData data : new Array.ArrayIterator<>(WorldData.getAllEnemies())) {
                // Content filter tables (user spec 2026-08-12): an Include=N enemy never enters
                // the random-spawn pool. Safe for quests - quest-boosted spawns go through
                // getExtraSpawnEnemy()/AdventureQuestController, not this list.
                if (!forge.adventure.util.ContentFilterTables.isEnemyIncluded(data.getName()))
                    continue;
                for (String enemyName : enemies) {
                    if (data.getName().equals(enemyName)) {
                        enemyList.add(data);
                        break;
                    }
                }
                //Adding enemy with 0 spawn rate allows quests to boost them and add to pool temporarily.
                EnemyData zeroSpawnRate = new EnemyData(data);
                zeroSpawnRate.spawnRate = 0.0f;
                enemyList.add(zeroSpawnRate);
            }
        }
        return enemyList;
    }

    public ArrayList<PointOfInterestData> getPointsOfInterest() {
        if (pointOfInterestList == null) {
            pointOfInterestList = new ArrayList<PointOfInterestData>();
            if (pointsOfInterest == null)
                return pointOfInterestList;
            Array<PointOfInterestData> allTowns = PointOfInterestData.getAllPointOfInterest();
            for (PointOfInterestData data : new Array.ArrayIterator<>(allTowns)) {
                for (String poiName : pointsOfInterest) {
                    if (data.name.equals(poiName)) {
                        pointOfInterestList.add(data);
                        break;
                    }
                }
            }
        }
        ArrayList<PointOfInterestData> cavesDungeon = new ArrayList<>();
        for (PointOfInterestData data : pointOfInterestList) {
            if ("cave".equalsIgnoreCase(data.type) || "dungeon".equalsIgnoreCase(data.type)) {
                cavesDungeon.add(data);
            }
        }
        pointOfInterestList.removeAll(cavesDungeon);
        pointOfInterestList.addAll(cavesDungeon); //move to bottom..
        return pointOfInterestList;
    }

    public EnemyData getExtraSpawnEnemy(float difficultyFactor) {
        //todo: implement difficultyFactor
        List<EnemyData> extraSpawnEnemies = AdventureQuestController.instance().getExtraQuestSpawns(difficultyFactor);
        if (extraSpawnEnemies.isEmpty())
            return null;
        return Aggregates.random(extraSpawnEnemies); //fallback, shouldn't reach this point but guarantee that we return something
    }

    public EnemyData getEnemy(float difficultyFactor) {
        List<EnemyData> filteredEnemies = new ArrayList<>();
        for (EnemyData data : enemyList ){
            if (data.difficulty <= difficultyFactor) {
                filteredEnemies.add(data);
            }
        }
        // If no enemies match the criteria, fallback to a random enemy from the original list.
        // Prefer spawnRate>0 entries (2026-08-27): enemyList carries a zero-spawn-rate clone of
        // EVERY enemy in the game (see getEnemyList's quest-boost mechanism), so an unfiltered
        // uniform pick here was a difficulty-blind side door for "Legends" catalog entries.
        if (filteredEnemies.isEmpty()) {
            List<EnemyData> spawnable = new ArrayList<>();
            for (EnemyData data : enemyList) {
                if (data.spawnRate > 0f)
                    spawnable.add(data);
            }
            return Aggregates.random(spawnable.isEmpty() ? enemyList : spawnable);
        }

        // Weighted-spawn tier system (2026-08-23, opt-in via SpawnTierWeighting.isEnabled();
        // Layer 3 redesigned 2026-08-25): reshapes the per-candidate weight fed into the pick
        // below, WITHOUT changing this method's signature or either of its two fallback guards -
        // TerritoryControl.reThemedEnemyFor() (a second, separate caller of this exact 1-arg
        // method) and any other unaudited caller keep compiling and behaving unchanged when the
        // feature is off, and correctly inherit the new weighting when it's on, since they call
        // the same method.
        //
        // Conceptually two sequential rolls - tier first, then a specific monster within that
        // tier - collapsed into one flat weighted pick, which is mathematically identical to
        // rolling them separately: each candidate's final weight is targetForItsTier * itsShare-
        // within-that-tier, so a single weighted draw over the combined array picks a tier with
        // probability proportional to that tier's total weight, and (conditional on that tier)
        // picks a candidate with probability proportional to its own share - exactly as if two
        // separate rolls had been made. See SpawnTierWeighting.java for the full mechanism (week
        // progression, territory/reputation modifier, and rawSpawnWeight()'s uniform-baseline-
        // permanently-halved-per-kill within-tier share).
        float[] effectiveWeights = new float[filteredEnemies.size()];
        float totalDistribution = 0.0f;
        if (SpawnTierWeighting.isEnabled()) {
            World world = WorldSave.getCurrentSave().getWorld();
            int week = SpawnTierWeighting.currentWeek(world);
            // Non-exempt candidate count per tier - the uniform baseline's denominator (1/N).
            Map<String, Integer> countByTier = new HashMap<>();
            for (EnemyData data : filteredEnemies) {
                if (SpawnTierWeighting.isExempt(data))
                    continue; // bosses/quest-tagged don't count toward - or get scaled by - tier weighting
                countByTier.merge(data.tier, 1, Integer::sum);
            }
            // Each non-exempt candidate's raw (pre-normalization) within-tier weight, and each
            // tier's raw weight sum - the denominator that turns raw weight into an actual share.
            float[] rawWeights = new float[filteredEnemies.size()];
            Map<String, Float> rawWeightSumByTier = new HashMap<>();
            for (int i = 0; i < filteredEnemies.size(); i++) {
                EnemyData data = filteredEnemies.get(i);
                if (SpawnTierWeighting.isExempt(data))
                    continue;
                int n = countByTier.getOrDefault(data.tier, 0);
                float raw = SpawnTierWeighting.rawSpawnWeight(data, n);
                rawWeights[i] = raw;
                rawWeightSumByTier.merge(data.tier, raw, Float::sum);
            }
            Map<String, Float> targetByTier = new HashMap<>();
            for (String tier : SpawnTierWeighting.tiers())
                targetByTier.put(tier, SpawnTierWeighting.targetTierWeight(tier, week, name));
            for (int i = 0; i < filteredEnemies.size(); i++) {
                EnemyData data = filteredEnemies.get(i);
                float weight;
                if (SpawnTierWeighting.isExempt(data)) {
                    weight = data.spawnRate; // exempt: unaffected, identical to the feature being off
                } else {
                    float rawSum = rawWeightSumByTier.getOrDefault(data.tier, 0f);
                    float target = targetByTier.getOrDefault(data.tier, 0f);
                    float share = rawSum > 0f ? (rawWeights[i] / rawSum) : 0f;
                    weight = target * share;
                }
                effectiveWeights[i] = weight;
                totalDistribution += weight;
            }
        } else {
            for (int i = 0; i < filteredEnemies.size(); i++) {
                effectiveWeights[i] = filteredEnemies.get(i).spawnRate;
                totalDistribution += effectiveWeights[i];
            }
        }

        // If every matching enemy's effective weight is 0 (e.g. a biome whose own "enemies" list
        // is empty and only zero-spawn-rate quest-boost copies matched, OR every eligible tier is
        // fully zeroed out this early under the weighting system above), the weighted pick below
        // would degenerate to always index 0 - f starts at 0 and "f <= 0.0f" is true immediately.
        // Pick uniformly at random among them instead of always the same one.
        if (totalDistribution <= 0.0f) {
            return Aggregates.random(filteredEnemies);
        }

        // Perform weighted random selection
        float f = totalDistribution * rand.nextFloat();
        int i = 0;
        for (; i < filteredEnemies.size(); i++) {
            f -= effectiveWeights[i];
            if (f <= 0.0f) {
                return filteredEnemies.get(i);
            }
        }

        // Fallback, should not normally reach here
        return Aggregates.random(filteredEnemies);
    }

    private ArrayList<String> unusedTownNames;
    public String getNewTownName() {
        String newName = Aggregates.removeRandom(getUnusedTownNames());
        if (newName == null) {
            // Pool ran dry - removeRandom on an empty list returns null, and a null display name
            // silently bakes the POI template's generic name ("Waste Town Generic") into every
            // remaining town. Reload the full list and keep going: a repeated town name is far
            // better than a nameless one. The pool can only run dry mid-generation when world-gen's
            // "Can not place POI ...Rerunning" restart has already drained it (each rerun discards
            // its placed towns but not the names they consumed) - see also resetTownNamePool().
            unusedTownNames = null;
            newName = Aggregates.removeRandom(getUnusedTownNames());
        }
        return newName;
    }

    /**
     * Restores the full name pool from disk. World-gen's placement-restart path calls this so
     * every placement pass starts with the complete list instead of inheriting the drain from
     * discarded passes (names consumed by a discarded pass were never kept by anything).
     */
    public void resetTownNamePool() {
        unusedTownNames = null;
    }

    public ArrayList<String> getUnusedTownNames() {
        if (unusedTownNames == null) {
            unusedTownNames = WorldData.getTownNames(this.name);
        }
        return unusedTownNames;
    }
}