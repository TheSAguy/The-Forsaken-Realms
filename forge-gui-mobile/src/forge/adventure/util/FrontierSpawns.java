package forge.adventure.util;

import forge.adventure.data.EnemyData;
import forge.adventure.data.FrontierSpawnData;

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
 * <p>
 * <b>Round 375:</b> only the membership rule is left here. Where, how often and which legend is sighted is the legend
 * table's business now ({@link LegendSpawns}, config tables/legends.json) - the per-status shares this class used to
 * append to every land's ordinary roll are gone, and so is the colorless members' wasteland: every legend roams only
 * the land of a color that is Unhappy or at War with the player.
 */
public class FrontierSpawns {
    private FrontierSpawns() {}

    private static FrontierSpawnData data() {
        return Config.instance().getFrontierSpawnData();
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
}
