package forge.adventure.util;

import forge.adventure.data.EnemyData;
import forge.adventure.data.WorldData;
import forge.adventure.pointofintrest.PointOfInterest;
import forge.adventure.world.World;
import forge.adventure.world.WorldSave;
import forge.util.MyRandom;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Cave champions (round 139, user spec 2026-09-07: "let's go and add all these spawnRate &lt;= 0
 * to those [caves]. Maybe not 100% spawn chance, but let's say 25% for one of them to appear.
 * This way they will exist out there at some point and they are not wasted. This also gives caves
 * a more dangerous proposition").
 * <p>
 * A cave rolls ONCE, the first time it is entered, for a single arena-exclusive enemy to take
 * over one of its ordinary roamers. The result is remembered per point of interest in
 * {@link World#getCaveChampion()} - both outcomes, so walking back out and in again can neither
 * re-roll a cave that came up empty nor farm a fresh champion out of one that already paid.
 * A cave that later despawns and is replaced comes back as a new POI id, and rolls again.
 * <p>
 * Rolling a NAME rather than promoting on the spot is what makes that possible: the champion has
 * to survive a map reload, and an EnemyData reference would not.
 */
public class CaveChampions {
    private CaveChampions() {}

    public static float chance() {
        return Config.instance().getConfigData().caveChampionChance;
    }

    public static boolean isEnabled() {
        return chance() > 0f;
    }

    public static boolean isCave(PointOfInterest poi) {
        return poi != null && poi.getData() != null && "cave".equalsIgnoreCase(poi.getData().type);
    }

    /**
     * This cave's champion, rolling for one on the first visit. Returns null when the cave rolled
     * empty, when it has already been rolled and came up empty, or when nothing in the catalog is
     * eligible at the player's current rank.
     *
     * @param difficultyFactor the player's rank, same value BiomeData.getEnemy() gates on - a
     *                         champion never outranks what the overworld would already throw
     */
    public static EnemyData championFor(PointOfInterest poi, float difficultyFactor) {
        if (!isEnabled() || !isCave(poi))
            return null;
        World world = WorldSave.getCurrentSave().getWorld();
        if (world == null)
            return null;
        Map<String, String> rolled = world.getCaveChampion();
        String key = poi.getID();
        if (rolled.containsKey(key)) {
            String name = rolled.get(key);
            return name == null || name.isEmpty() ? null : WorldData.getEnemy(name);
        }
        if (MyRandom.getRandom().nextFloat() >= chance()) {
            rolled.put(key, "");
            System.out.println("[TFR-CaveChampion] " + poi.getData().name + " (" + key + ") rolled no champion");
            return null;
        }
        List<EnemyData> pool = poolFor(poi, difficultyFactor, world);
        if (pool.isEmpty()) {
            rolled.put(key, "");
            System.out.println("[TFR-CaveChampion] " + poi.getData().name + " (" + key
                    + ") won its roll but no champion is eligible at rank " + difficultyFactor);
            return null;
        }
        EnemyData pick = pool.get(MyRandom.getRandom().nextInt(pool.size()));
        rolled.put(key, pick.getName());
        System.out.println("[TFR-CaveChampion] " + poi.getData().name + " (" + key + ") drew "
                + pick.getName() + " (" + EnemyData.tierDisplayName(pick.tier) + ", life " + pick.life
                + ") from " + pool.size() + " eligible");
        return pick;
    }

    /**
     * Round 166: the champion fell, so this cave's roll is spent for good. Recorded as an EMPTY roll,
     * which championFor() already reads as "no champion here", so a later visit cannot promote the
     * same champion onto another placement - the farming the round-160 review found (the killed
     * placement leaves MapStage.prepareCaveChampion()'s candidate list, and the placement hash then
     * lands on a different one). A cave that despawns and is replaced still rolls afresh as a new POI.
     */
    public static void onChampionDefeated(PointOfInterest poi, String name) {
        if (poi == null)
            return;
        World world = WorldSave.getCurrentSave().getWorld();
        if (world == null)
            return;
        world.getCaveChampion().put(poi.getID(), "");
        System.out.println("[TFR-CaveChampion] " + poi.getData().name + " (" + poi.getID() + "): " + name
                + " defeated - this cave's champion is gone for good");
    }

    /**
     * Every arena-exclusive enemy this cave could host, preferring the biome's own colour and
     * falling back to the whole eligible catalog when that colour has nobody left at this rank.
     * <p>
     * "Arena-exclusive" is the same test ArenaScene uses to decide a champion bounty is owed:
     * authored not to roam, and carrying rewards of its own. Bosses are excluded because they
     * belong to the encounters that placed them, and oversized sprites because a 3x model does
     * not fit down a cave corridor.
     */
    private static List<EnemyData> poolFor(PointOfInterest poi, float difficultyFactor, World world) {
        String biome = biomeNameOf(poi, world);
        String letter = colorLetterOf(biome);
        List<EnemyData> matching = new ArrayList<>();
        List<EnemyData> any = new ArrayList<>();
        for (EnemyData data : new com.badlogic.gdx.utils.Array.ArrayIterator<>(WorldData.getAllEnemies())) {
            if (data == null || data.spawnRate > 0f || data.boss)
                continue;
            if (data.rewards == null || data.rewards.length == 0)
                continue;
            // Round 178: legends stay out (they were the scale > 1.5 models; every sprite is tier-sized now,
            // so the flag keeps the same enemies out that the scale test used to).
            if (data.legend)
                continue;
            if (data.difficulty > difficultyFactor)
                continue;
            if (!ContentFilterTables.isEnemyIncluded(data.getName()))
                continue;
            any.add(data);
            if (letter != null && data.colors != null && data.colors.contains(letter))
                matching.add(data);
        }
        return matching.isEmpty() ? any : matching;
    }

    /** The biome this POI stands in, by the same lookup MapStage uses for its own biome fallback. */
    private static String biomeNameOf(PointOfInterest poi, World world) {
        try {
            com.badlogic.gdx.math.Vector2 pos = poi.getPosition();
            int biomeIndex = World.highestBiome(world.getBiome((int) pos.x / world.getTileSize(),
                    (int) pos.y / world.getTileSize()));
            return world.getData().GetBiomes().get(biomeIndex).name;
        } catch (Exception e) {
            return null; // off-map or a biome-less POI: fall back to the whole catalog
        }
    }

    /** Biome name -> the colour letter enemies.json writes in EnemyData.colors, null for the
     *  wasteland and player biomes, which have no colour of their own to match on. */
    private static String colorLetterOf(String biome) {
        if (biome == null)
            return null;
        switch (biome) {
            case "white": return "W";
            case "blue":  return "U";
            case "black": return "B";
            case "red":   return "R";
            case "green": return "G";
            default:      return null;
        }
    }
}
