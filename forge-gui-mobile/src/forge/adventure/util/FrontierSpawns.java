package forge.adventure.util;

import forge.adventure.data.EnemyData;
import forge.adventure.data.FrontierSpawnData;
import forge.adventure.data.WorldData;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Frontier spawns (round 142, user spec 2026-09-07). An audit found 111 enemies in the catalog
 * that a player could reach through no route at all - not roaming, not placed on a map, not in an
 * arena pool, not named in a quest, and excluded from both of round 139/141's new pools. Every one
 * failed the same two lines: not Mythic tier, so barred from the Chest's Dangerous-Enemy pool, and
 * sprite scale over 1.5, so barred from the cave-champion pool that keeps 3x models out of
 * two-tile corridors. They are the plane's oversized legend/commander cycle - the dragon lords,
 * the Theros gods, the Slivers, Cromat - and 85 of them are multicoloured.
 * <p>
 * The user's ruling: <i>"just have them be spawnable in Unhappy and War state terrain. So the
 * multi colour would spawn in multiple colour zones. Colourless - make those spawnable in Neutral
 * terrain."</i> Which is a good fit for what they are. A hostile border is exactly where an
 * oversized legend belongs, the multicoloured majority gets several homes instead of none, and
 * tying it to reputation means the player decides when to meet them.
 * <p>
 * <b>Defined by predicate, not by a list.</b> war_champions.json names its 25 because they were
 * hand-cast; this set is 126 entries wide and would go stale the moment an enemy is added or
 * re-tiered, so {@link #isCandidate} re-derives it every roll from the same properties that made
 * them unreachable in the first place. The predicate also catches 15 enemies that ARE already
 * placed on a map - harmless, and arguably right: a hand-placed legend roaming a war zone is the
 * same creature in the same world.
 * <p>
 * Like {@link WarChampions} this grants weight from the outside and never edits {@code spawnRate},
 * because that field is simultaneously SpawnTierWeighting's "never rolls on its own" exclusion and
 * ArenaScene's champion-bounty flag.
 */
public class FrontierSpawns {
    private FrontierSpawns() {}

    /** The player's own territory. Round 173: not "neutral terrain" - it has no reputation at all. */
    private static final String PLAYER_BIOME = "player";

    private static FrontierSpawnData data() {
        return Config.instance().getFrontierSpawnData();
    }

    public static boolean isEnabled() {
        FrontierSpawnData d = data();
        return d != null && (d.unhappyShare > 0f || d.warShare > 0f || d.neutralColorlessShare > 0f);
    }

    /**
     * Is this enemy one of the stranded legends? The two clauses that matter are {@code tier} and
     * {@code legend} (round 178; it was {@code scale} over 1.5 before sizes went one-per-tier) -
     * together they are exactly what excluded these from the chest and cave pools.
     * The life ceiling keeps the hand-placed Eldrazi titans (70) out while admitting every
     * genuinely unreachable entry (the largest is 50).
     */
    public static boolean isCandidate(EnemyData e) {
        FrontierSpawnData d = data();
        if (d == null || e == null)
            return false;
        if (e.spawnRate > 0f || e.boss)
            return false;
        if (e.rewards == null || e.rewards.length == 0)
            return false;
        if (e.questTags != null && e.questTags.length > 0)
            return false;
        if ("Mythic".equals(e.tier))
            return false;          // Mythics already ride the Chest's Dangerous-Enemy pool
        if (!e.legend)
            return false;          // round 178: the legend flag - sprite scale no longer says "huge model"
        return e.life < (d.maxLife <= 0 ? 60 : d.maxLife);
    }

    /** The share this biome's situation grants, or 0 when it grants none. */
    public static float shareFor(String biomeName) {
        FrontierSpawnData d = data();
        if (d == null || biomeName == null || !ColorReputation.isEnabled())
            return 0f;
        // Round 173 (review S1): the feature never fired before this round, so this case was never
        // seen - statusOf() is null for the player's own biome as well as the wasteland, which would
        // have sent the colorless legends into the player's home. Home stays quiet, like Happy and
        // Partner territory; only the wasteland (no owner) and a color at NEUTRAL take that share.
        if (PLAYER_BIOME.equals(biomeName))
            return 0f;
        ColorReputation.Status status = statusOf(biomeName);
        if (status == null)
            return clamp(d.neutralColorlessShare);   // the wasteland: no owner, so always neutral
        switch (status) {
            case WAR:     return clamp(d.warShare);
            case UNHAPPY: return clamp(d.unhappyShare);
            case NEUTRAL: return clamp(d.neutralColorlessShare);
            default:      return 0f;                 // Happy/Partner territory stays quiet
        }
    }

    /**
     * Appends this biome's eligible frontier spawns to a candidate list and returns what was
     * added. Colour matching is per-letter, so a WUBRG legend is eligible in all five colour
     * biomes and a UG one in two - the "multi colour would spawn in multiple colour zones" half of
     * the spec. In NEUTRAL terrain only the colourless members qualify; a coloured legend has a
     * colour's border to haunt instead.
     * <p>
     * Unlike the war champions these respect {@code difficultyFactor}: they are ordinary Rare and
     * Uncommon tier enemies, so the rank gate BiomeData.getEnemy() already applies is the right
     * one for them. The caller has already filtered its own list by it, hence the check here.
     */
    public static List<EnemyData> injectFor(String biomeName, List<EnemyData> candidates, float difficultyFactor) {
        List<EnemyData> added = new ArrayList<>();
        if (!isEnabled() || candidates == null || shareFor(biomeName) <= 0f)
            return added;
        boolean neutral = statusOf(biomeName) == null || statusOf(biomeName) == ColorReputation.Status.NEUTRAL;
        String letter = colorLetterOf(biomeName);
        Set<String> present = new HashSet<>();
        for (EnemyData e : candidates) {
            if (e != null)
                present.add(e.getName());
        }
        // A war champion this biome is fielding right now belongs to WarChampions - it is already at the
        // list's tail with its own share, and BiomeData's index arithmetic needs the two groups disjoint.
        // (No enemy is both today: every champion is Archmage tier, which isCandidate() refuses.)
        Set<String> warCast = WarChampions.isBiomeAtWar(biomeName) ? WarChampions.championNames(biomeName) : new HashSet<>();
        Set<String> addedNames = new HashSet<>();
        for (EnemyData e : new com.badlogic.gdx.utils.Array.ArrayIterator<>(WorldData.getAllEnemies())) {
            if (e == null || !isCandidate(e) || warCast.contains(e.getName()) || addedNames.contains(e.getName()))
                continue;
            if (e.difficulty > difficultyFactor)
                continue;
            String colors = e.colors == null ? "" : e.colors.toUpperCase();
            boolean colorless = colors.isEmpty() || colors.equals("C");
            boolean matches = neutral ? colorless : (letter != null && colors.contains(letter));
            if (!matches)
                continue;
            if (!ContentFilterTables.isEnemyIncluded(e.getName()))
                continue;
            // Round 173 (code review S1, user: "bring them back"): until this round the feature never
            // added a single enemy. The candidate list carries a zero-spawn-rate copy of EVERY catalog
            // enemy at or below the player's rank (BiomeData.getEnemyList()'s quest-boost mechanism),
            // and the old "skip if present" test and the rank test above were exact complements: every
            // legend was either already in the list, weightless, or above the rank. Same fix as round
            // 166's war champions (review S7): drop the weightless copy and append the legend, so it is
            // counted once and sits at the tail, where BiomeData grants the frontier share.
            String name = e.getName();
            if (present.contains(name))
                candidates.removeIf(d -> d != null && name.equals(d.getName()));
            candidates.add(e);
            added.add(e);
            addedNames.add(name);
            present.add(name);
        }
        return added;
    }

    /** Same solve WarChampions uses: w / (w + rest) = share, so the share tracks the live pool. */
    public static float shareWeight(float otherWeightTotal, String biomeName) {
        float share = shareFor(biomeName);
        if (share <= 0f || otherWeightTotal <= 0f)
            return 0f;
        return otherWeightTotal * share / (1f - share);
    }

    /** This biome's reputation status, or null when the biome has no AI owner (wasteland/player). */
    private static ColorReputation.Status statusOf(String biomeName) {
        for (String color : ColorReputation.COLORS) {
            if (color.equals(biomeName))
                return ColorReputation.getStatus(color);
        }
        return null;
    }

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

    private static float clamp(float share) {
        return share <= 0f ? 0f : Math.min(share, 0.9f);
    }
}
