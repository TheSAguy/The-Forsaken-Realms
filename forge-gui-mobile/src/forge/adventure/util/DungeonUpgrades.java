package forge.adventure.util;

import forge.adventure.data.BiomeData;
import forge.adventure.data.EnemyData;
import forge.adventure.data.TuningData;
import forge.adventure.pointofintrest.PointOfInterest;
import forge.adventure.world.World;
import forge.util.MyRandom;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Round 447 (the user: "a lot, if not most dungeons I visit only have apprentice and adepts, so i think we need to
 * implement a similar scale of what can spawn and depending on your win streak, it could go up by one or two levels.
 * At 30+ win streak, going from a apprentice to adept won't have much of an impact"; "It should only be for the active
 * dungeon, once it cycles, it will reset").
 * <p>
 * On the first visit of a level of a rotating dungeon or cave, each ordinary placement rolls: the highest
 * TuningData.dungeonUpgradeWins the wins in a row reach picks its chances of stepping up two ranks
 * (dungeonUpgradePlusTwo) or one (dungeonUpgradePlusOne), capped at Archmage. The new creature comes from the land the
 * place stands on now (the roster a re-theme draws from), at the target rank, within the spawn gate
 * (SpawnTierWeighting.effectiveRank), open in this week's spawn row (week 1 has no Masters), and of the same kind where
 * the land has one: the most descriptive tags shared with the placement, so Undead stays Undead. With no candidate two
 * ranks up it takes one. MapStage records the result in the
 * place's roster, so later visits meet the same creatures, and a rotation clears every level's roster
 * (DungeonRotation.hidePoi) - the next incarnation rolls afresh. MapStage does not ask for placements it keeps as
 * authored, castle or cave champions, or dialog carriers; special creatures (DungeonSources.isSpecial) are skipped here.
 */
public final class DungeonUpgrades {
    private DungeonUpgrades() {
    }

    /** The creature that takes this placement's place on this visit, or null to keep the one placed. */
    public static EnemyData upgrade(World world, PointOfInterest poi, EnemyData placed, int objectId) {
        if (world == null || poi == null || placed == null || !DungeonRotation.isRotatableData(poi.getData())
                || DungeonSources.isSpecial(placed))
            return null;
        TuningData tuning = Config.instance().getTuningData();
        if (tuning == null || tuning.dungeonUpgradeWins == null || Current.player() == null)
            return null;
        int streak = Current.player().notorietyStreak();
        int row = -1;
        for (int i = 0; i < tuning.dungeonUpgradeWins.length; i++)
            if (streak >= tuning.dungeonUpgradeWins[i])
                row = i;
        if (row < 0)
            return null;
        int plusOne = percent(tuning.dungeonUpgradePlusOne, row);
        int plusTwo = percent(tuning.dungeonUpgradePlusTwo, row);
        int from = EnemyData.tierRank(placed.tier);
        if (from >= 3)
            return null;
        Random rand = MyRandom.getRandom();
        int roll = rand.nextInt(100);
        int shift = roll < plusTwo ? 2 : roll < plusTwo + plusOne ? 1 : 0;
        if (shift == 0)
            return null;
        BiomeData land = landOf(world, poi);
        if (land == null)
            return null;
        float ceiling = SpawnTierWeighting.effectiveRank(Current.player().getStatistic().rank());
        int week = SpawnTierWeighting.currentWeek(world);
        for (int to = Math.min(3, from + shift); to > from; to--) {
            // The week brackets stay the one authority on early pacing (round 418; the attack mages' clamp does the
            // same): a rank this week's row closes - no Masters in week 1 - is not reached by an upgrade either.
            if (SpawnTierWeighting.isEnabled() && SpawnTierWeighting.targetTierWeight(SpawnTierWeighting.tiers()[to], week, null) <= 0f)
                continue;
            EnemyData pick = pick(land, placed, SpawnTierWeighting.tiers()[to], ceiling, rand);
            if (pick != null) {
                System.out.println("[TFR-DungeonUpgrade] " + poi.getDisplayName() + " #" + objectId + ": " + placed.getName()
                        + " (" + EnemyData.tierDisplayName(placed.tier) + ") -> " + pick.getName() + " ("
                        + EnemyData.tierDisplayName(pick.tier) + "), +" + (to - from)
                        + (to - from < shift ? " (rolled +" + shift + ")" : "") + " - " + streak + " wins in a row (+1 "
                        + plusOne + "%, +2 " + plusTwo + "%), " + land.name + " roster, rank " + ceiling);
                return pick;
            }
        }
        System.out.println("[TFR-DungeonUpgrade] " + poi.getDisplayName() + " #" + objectId + ": " + placed.getName()
                + " rolled +" + shift + " but week " + week + " / " + land.name + " has no higher rank open within rank "
                + ceiling + " - kept");
        return null;
    }

    private static int percent(int[] list, int row) {
        return list == null || row >= list.length ? 0 : Math.max(0, list[row]);
    }

    /** The land the place stands on now - TerritoryControl.currentColorAtPoi's biome. */
    private static BiomeData landOf(World world, PointOfInterest poi) {
        String color = TerritoryControl.currentColorAtPoi(world, poi);
        if (color == null)
            return null;
        for (BiomeData biome : world.getData().GetBiomes())
            if (color.equals(biome.name))
                return biome;
        return null;
    }

    /** A spawning creature of the land at this rank and within the gate, preferring the placement's own kind. */
    private static EnemyData pick(BiomeData land, EnemyData placed, String tier, float ceiling, Random rand) {
        Set<String> kinds = kindTags(placed);
        List<EnemyData> best = new ArrayList<>();
        int bestShared = -1;
        Set<String> seen = new HashSet<>();
        for (EnemyData e : land.getEnemyList()) {
            if (e == null || e.spawnRate <= 0f || !tier.equals(e.tier) || e.difficulty > ceiling
                    || DungeonSources.isSpecial(e) || !seen.add(e.getName()))
                continue;
            int shared = 0;
            for (String tag : kindTags(e))
                if (kinds.contains(tag))
                    shared++;
            if (shared > bestShared) {
                best.clear();
                bestShared = shared;
            }
            if (shared == bestShared)
                best.add(e);
        }
        return best.isEmpty() ? null : best.get(rand.nextInt(best.size()));
    }

    /** An enemy's descriptive tags - its questTags without the Biome* and Identity* ones. */
    private static Set<String> kindTags(EnemyData e) {
        Set<String> tags = new HashSet<>();
        if (e.questTags != null)
            for (String tag : e.questTags)
                if (tag != null && !tag.startsWith("Biome") && !tag.startsWith("Identity"))
                    tags.add(tag);
        return tags;
    }
}
