package forge.adventure.util;

import com.badlogic.gdx.utils.Array;
import forge.adventure.data.EffectData;
import forge.adventure.data.EnemyData;
import forge.adventure.data.LegendSpawnData;
import forge.adventure.data.WorldData;
import forge.adventure.world.World;
import org.apache.commons.lang3.tuple.Pair;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * The legend table (round 375). The user: <i>"I think we need to have Legends have their own spawn-pool/table to
 * control their appearance better. They should be pretty Rare and once one spawns, the odds of it spawning again
 * before the rest has spawned once should be very low. There should never be 2 of the same kind on the map at the same
 * time. Legends should all start with a Gemstone Mine in play."</i> - and, asked about the best-of-3 "mini-boss"
 * fights, gate them behind Unhappy and War terrain.
 * <p>
 * Until this round the legends - the 127 frontier legends (FrontierSpawns, round 142) and the 25 roaming champions
 * (RoamingChampions, round 311) - were weight shares mixed into every land's ordinary spawn roll, with no memory of
 * what had come before: about 1.5 champion rolls a day on any colored land at any reputation, up to seven frontier
 * rolls a day at War, and a wasteland pool of exactly three colorless legends (Traxos over and over). Now they have a
 * roll of their own, made by WorldStage.rollLegendSighting() once per spawn roll, before the ordinary pick:
 * <ul>
 * <li><b>Where:</b> only on land of a color that is UNHAPPY or at WAR with the player, judged at the spot the legend
 * would stand on - never the wasteland, the player's land or a friendly color's. A legend qualifies when it carries
 * that color's letter; the colorless ones qualify in every hostile land.</li>
 * <li><b>How often:</b> legends.json unhappyChance / warChance per roll, no sooner than cooldownDays after the last
 * sighting, and no more than maxAlive at once. The rank gate stays (difficulty against PlayerStatistic.rank()).</li>
 * <li><b>Which:</b> weighted by the save's sighting count - repeatWeight to the power of how many more times a legend
 * has been sighted than the least-sighted one in the same draw, so a legend comes back only once the rest of its pool
 * has had its turn. One already roaming is left out of the draw.</li>
 * <li><b>How it behaves:</b> it is sighted spawnMinTiles..spawnMaxTiles away and holds its ground until the player
 * comes within chaseTiles (WorldStage's enemy loop), and its duels start with startBattleWithCard in play
 * (DuelScene) - neither applies to hand-placed dungeon legends or arena champions.</li>
 * </ul>
 * Grep forge.log for [TFR-LegendTable].
 */
public final class LegendSpawns {
    private LegendSpawns() {}

    private static LegendSpawnData cachedFor;
    private static EffectData cachedEffect;

    private static LegendSpawnData data() {
        return Config.instance().getLegendSpawnData();
    }

    public static boolean isEnabled() {
        LegendSpawnData d = data();
        return d != null && (d.unhappyChance > 0f || d.warChance > 0f);
    }

    /** A member of the legend table - a frontier legend or a roaming champion. */
    public static boolean isMember(EnemyData e) {
        return RoamingChampions.isLegend(e);
    }

    /** The chance per spawn roll for a legend standing in this land: a color at UNHAPPY or WAR, 0 anywhere else. */
    public static float chanceFor(String landName) {
        LegendSpawnData d = data();
        if (d == null || colorLetterOf(landName) == null || !ColorReputation.isEnabled())
            return 0f;
        switch (ColorReputation.getStatus(landName)) {
            case WAR:     return clamp(d.warChance);
            case UNHAPPY: return clamp(d.unhappyChance);
            default:      return 0f;
        }
    }

    /** True once cooldownDays have passed since the last sighting (always true before the first). */
    public static boolean cooledDown(World world) {
        LegendSpawnData d = data();
        int last = world.getLastLegendSightingDay();
        return d == null || last < 0 || world.getCurrentDay() - last >= d.cooldownDays;
    }

    /** The day the cooldown ends, for the log. */
    public static int nextSightingDay(World world) {
        LegendSpawnData d = data();
        int last = world.getLastLegendSightingDay();
        return last < 0 || d == null ? world.getCurrentDay() : last + d.cooldownDays;
    }

    /** True while fewer than maxAlive legends roam (maxAlive 0 or less: no cap). */
    public static boolean roomFor(int alive) {
        LegendSpawnData d = data();
        return d == null || d.maxAlive <= 0 || alive < d.maxAlive;
    }

    /**
     * The legends that may be sighted in this land now: members carrying its color's letter (or colorless), at or below
     * the player's rank, allowed by the content filter, and not already roaming ({@code alive}, catalog names).
     */
    public static List<EnemyData> candidatesFor(String landName, float rank, Set<String> alive) {
        List<EnemyData> out = new ArrayList<>();
        String letter = colorLetterOf(landName);
        if (letter == null)
            return out;
        Set<String> seen = new HashSet<>();
        for (EnemyData e : new Array.ArrayIterator<>(WorldData.getAllEnemies())) {
            if (e == null || e.name == null || !seen.add(e.name) || !isMember(e))
                continue;
            if (alive != null && alive.contains(e.name))
                continue;
            if (e.difficulty > rank)
                continue;
            if (!ContentFilterTables.isEnemyIncluded(e.name) || !ContentFilterTables.isEnemyIncluded(e.getName()))
                continue;
            String colors = e.colors == null ? "" : e.colors.toUpperCase();
            boolean colorless = colors.isEmpty() || colors.equals("C");
            if (colorless || colors.contains(letter))
                out.add(e);
        }
        return out;
    }

    /** The draw: least-sighted first. Returns null for an empty list. */
    public static Pick pick(World world, List<EnemyData> candidates, Random rand) {
        if (candidates == null || candidates.isEmpty())
            return null;
        seedIfNeeded(world);
        LegendSpawnData d = data();
        float repeat = d == null || d.repeatWeight <= 0f ? 0.05f : Math.min(1f, d.repeatWeight);
        int min = Integer.MAX_VALUE;
        for (EnemyData e : candidates)
            min = Math.min(min, sightings(world, e.name));
        float[] weights = new float[candidates.size()];
        float total = 0f;
        int leastSeen = 0;
        for (int i = 0; i < weights.length; i++) {
            int extra = sightings(world, candidates.get(i).name) - min;
            if (extra == 0)
                leastSeen++;
            weights[i] = (float) Math.pow(repeat, extra);
            total += weights[i];
        }
        float f = total * rand.nextFloat();
        int chosen = weights.length - 1;
        for (int i = 0; i < weights.length; i++) {
            f -= weights[i];
            if (f <= 0f) {
                chosen = i;
                break;
            }
        }
        return new Pick(candidates.get(chosen), weights[chosen] / total, min, leastSeen, candidates.size());
    }

    /** One draw's result, with what the log line reports. */
    public static final class Pick {
        public final EnemyData enemy;
        public final float share;
        public final int fewestSightings;
        public final int leastSeen;
        public final int poolSize;

        Pick(EnemyData enemy, float share, int fewestSightings, int leastSeen, int poolSize) {
            this.enemy = enemy;
            this.share = share;
            this.fewestSightings = fewestSightings;
            this.leastSeen = leastSeen;
            this.poolSize = poolSize;
        }
    }

    public static int sightings(World world, String catalogName) {
        Integer n = world.getLegendSightingCount().get(catalogName);
        return n == null ? 0 : n;
    }

    /** Counts a sighting and starts the cooldown - every legend sighting, whichever path placed it. */
    public static void recordSighting(World world, EnemyData e) {
        if (world == null || e == null || e.name == null)
            return;
        seedIfNeeded(world);
        int n = world.getLegendSightingCount().merge(e.name, 1, Integer::sum);
        world.setLastLegendSightingDay(world.getCurrentDay());
        System.out.println("[TFR-LegendTable] " + e.name + " sighted - sighting #" + n + " this game; the next legend"
                + " can come from day " + nextSightingDay(world));
    }

    /**
     * A save from before round 375 has no sighting counts: they start from the win/loss record (times fought, keyed by
     * the display name), so a legend the player has already met several times - the user's Traxos - does not open the
     * new table at even odds with the ones never seen. Once per save.
     */
    private static void seedIfNeeded(World world) {
        if (world.isLegendSightingsSeeded())
            return;
        world.setLegendSightingsSeeded(true);
        Map<String, Pair<Integer, Integer>> record = Current.player() == null ? null
                : Current.player().getStatistic().getWinLossRecord();
        if (record == null || record.isEmpty())
            return;
        int seeded = 0;
        StringBuilder names = new StringBuilder();
        Set<String> seen = new HashSet<>();
        for (EnemyData e : new Array.ArrayIterator<>(WorldData.getAllEnemies())) {
            if (e == null || e.name == null || !seen.add(e.name) || !isMember(e))
                continue;
            Pair<Integer, Integer> wl = record.get(e.getName());
            int fought = wl == null ? 0 : wl.getLeft() + wl.getRight();
            if (fought <= 0)
                continue;
            world.getLegendSightingCount().merge(e.name, fought, Math::max);
            seeded++;
            if (names.length() < 400)
                names.append(names.length() == 0 ? "" : ", ").append(e.name).append(' ').append(fought).append('x');
        }
        System.out.println("[TFR-LegendTable] sighting counts seeded from the win/loss record: " + seeded
                + " legend(s) already met" + (seeded == 0 ? "" : " [" + names + "]"));
    }

    /** The legend's start-of-duel cards as an opponent effect (legends.json startBattleWithCard), or null for none. */
    public static EffectData duelEffect() {
        LegendSpawnData d = data();
        if (d != cachedFor) {
            cachedFor = d;
            cachedEffect = null;
            if (d != null && d.startBattleWithCard != null && d.startBattleWithCard.length > 0) {
                cachedEffect = new EffectData();
                cachedEffect.name = "Legend";
                cachedEffect.startBattleWithCard = d.startBattleWithCard;
            }
        }
        return cachedEffect;
    }

    /** How far out a legend is sighted, in tiles - a random distance in spawnMinTiles..spawnMaxTiles. */
    public static float spawnTiles(Random rand) {
        LegendSpawnData d = data();
        float min = d == null || d.spawnMinTiles <= 0f ? 12f : d.spawnMinTiles;
        float max = d == null ? 20f : Math.max(min, d.spawnMaxTiles);
        return min + (max - min) * rand.nextFloat();
    }

    public static float chaseTiles() {
        LegendSpawnData d = data();
        return d == null || d.chaseTiles <= 0f ? 5f : d.chaseTiles;
    }

    public static float leashTiles() {
        LegendSpawnData d = data();
        return d == null ? 8f : Math.max(chaseTiles(), d.leashTiles);
    }

    public static String colorLetterOf(String land) {
        if (land == null)
            return null;
        switch (land) {
            case "white": return "W";
            case "blue":  return "U";
            case "black": return "B";
            case "red":   return "R";
            case "green": return "G";
            default:      return null;
        }
    }

    private static float clamp(float chance) {
        return chance <= 0f ? 0f : Math.min(chance, 1f);
    }
}
