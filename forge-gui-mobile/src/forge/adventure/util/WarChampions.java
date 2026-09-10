package forge.adventure.util;

import forge.adventure.data.EnemyData;
import forge.adventure.data.WarChampionData;
import forge.adventure.data.WorldData;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * War champions (round 139, user spec 2026-09-07): a small hand-picked cast of the
 * arena-exclusive enemies - the ones authored with {@code spawnRate} 0, which is both the flag
 * that keeps them out of the roaming pool and the flag ArenaScene reads to decide a champion
 * bounty is owed - become roaming overworld encounters in their own colour's biome, but only
 * while that colour is at WAR with the player. Five per colour, and between them they take
 * {@link #share()} of that biome's spawn rolls for as long as the war lasts.
 * <p>
 * <b>Why a whitelist and not just a small spawnRate.</b> "Low probability spawn" is no longer
 * expressible through the data. Since the 2026-08-23 tier system,
 * {@link SpawnTierWeighting#rawSpawnWeight} gives every non-exempt candidate in a tier the SAME
 * uniform baseline and deliberately ignores its own {@code spawnRate}, so authoring one of these
 * at 0.01 would not make it rare - it would make it exactly as likely as any other Mythic in the
 * biome. 56 of the 92 arena champions are Mythic against a roaming Mythic pool of 61, so simply
 * unlocking them would have handed them roughly half of every Mythic encounter. The weight has
 * to be granted explicitly, which is what {@link #shareWeight} does.
 * <p>
 * <b>Why they stay {@code spawnRate} 0 in the data.</b> {@link SpawnTierWeighting#isExempt}
 * reads {@code spawnRate <= 0} as "never rolls on its own", and ArenaScene's bounty test is the
 * same comparison. Editing enemies.json would have silently cancelled the bounty AND let them
 * roam everywhere, at any time, at full uniform weight. Leaving the data at 0 and granting the
 * weight here keeps the exclusivity, the bounty and the war-time appearance three separate
 * decisions.
 * <p>
 * <b>Why they are appended rather than filtered in.</b> BiomeData.getEnemy() only considers
 * candidates whose {@code difficulty} is at or below the player's rank, and every arena champion
 * is difficulty 3 - which PlayerStatistic.rank() does not reach until 150 wins. Gating a war
 * appearance behind that on top of the war itself would have meant almost never. The war IS the
 * gate the user asked for, so these are appended after the rank filter has run.
 */
public class WarChampions {
    private WarChampions() {}

    private static WarChampionData data() {
        return Config.instance().getWarChampionData();
    }

    /** The configured fraction of a war-torn biome's spawn rolls the champions take, or 0 (off). */
    public static float share() {
        WarChampionData d = data();
        if (d == null || d.share <= 0f)
            return 0f;
        // A share of 1 would leave nothing for the rest of the biome and would make shareWeight()
        // divide by zero - clamp well short of that.
        return Math.min(d.share, 0.9f);
    }

    public static boolean isEnabled() {
        return share() > 0f && ColorReputation.isEnabled();
    }

    /**
     * Is this biome currently a war zone the champions ride in on? Only the five reputation
     * colours can be - the wasteland and player-owned biomes have no AI to declare war.
     */
    public static boolean isBiomeAtWar(String biomeName) {
        if (!isEnabled() || biomeName == null)
            return false;
        for (String color : ColorReputation.COLORS) {
            if (color.equals(biomeName))
                return ColorReputation.getStatus(color) == ColorReputation.Status.WAR;
        }
        return false;
    }

    /** The champion names cast for this biome's colour; empty when the colour has none. */
    public static Set<String> championNames(String biomeName) {
        Set<String> out = new HashSet<>();
        WarChampionData d = data();
        if (d == null || biomeName == null)
            return out;
        String[] names = d.forColor(biomeName);
        if (names == null)
            return out;
        for (String name : names) {
            if (name != null && !name.isEmpty())
                out.add(name);
        }
        return out;
    }

    /**
     * Appends this biome's war champions to a spawn-candidate list and returns the ones added, or
     * an empty list when no war is on. Skips a champion already in the list (so it can never be
     * counted twice) and one that has since been given a real {@code spawnRate} in the data (it
     * roams on its own terms then, and is no longer this feature's business).
     */
    public static List<EnemyData> injectFor(String biomeName, List<EnemyData> candidates) {
        List<EnemyData> added = new ArrayList<>();
        if (!isBiomeAtWar(biomeName) || candidates == null)
            return added;
        Set<String> cast = championNames(biomeName);
        if (cast.isEmpty())
            return added;
        Set<String> present = new HashSet<>();
        for (EnemyData data : candidates) {
            if (data != null)
                present.add(data.getName());
        }
        for (String name : cast) {
            EnemyData data = WorldData.getEnemy(name);
            if (data == null || data.spawnRate > 0f)
                continue;
            if (!ContentFilterTables.isEnemyIncluded(name))
                continue;
            // Round 166 (user: "War champions stop appearing once you pass 150 wins. Why?"): the biome's
            // candidate list carries a zero-spawn-rate clone of EVERY enemy at or below the player's rank,
            // and every champion is difficulty 3 - so from rank 3 (150 wins) the champion was already in
            // the list, weightless, and the old "skip if present" rule left it there. BiomeData grants the
            // champions' share only to the entries this method APPENDS, so the champion has to move to the
            // tail rather than be skipped. Removing the clone first keeps it counted exactly once.
            if (present.contains(name))
                candidates.removeIf(d -> d != null && name.equals(d.getName()));
            candidates.add(data);
            added.add(data);
            present.add(name);
        }
        return added;
    }

    /**
     * Total weight to hand the champions so they take exactly {@link #share()} of the roll, given
     * the weight everything else in this biome already carries. Solving
     * {@code w / (w + rest) = share} gives {@code w = rest * share / (1 - share)} - which is why
     * this takes the rest of the distribution rather than returning a flat number: the tier
     * targets it competes against move with the week bracket and the territory status, and a
     * fixed weight would drift away from the configured percentage as they did.
     *
     * @param otherWeightTotal summed effective weight of every non-champion candidate
     * @return the champions' combined weight, or 0 when there is nothing to scale against
     */
    public static float shareWeight(float otherWeightTotal) {
        float share = share();
        if (share <= 0f || otherWeightTotal <= 0f)
            return 0f;
        return otherWeightTotal * share / (1f - share);
    }
}
