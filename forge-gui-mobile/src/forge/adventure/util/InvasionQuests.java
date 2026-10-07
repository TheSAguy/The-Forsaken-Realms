package forge.adventure.util;

import forge.adventure.data.AdventureQuestData;
import forge.adventure.data.AdventureQuestStage;
import forge.adventure.data.DialogData;
import forge.adventure.data.EnemyData;
import forge.adventure.data.TuningData;
import forge.adventure.data.WorldData;
import forge.adventure.world.WorldSave;
import forge.util.Aggregates;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.TreeSet;

/**
 * Round 461 - invasion quests that answer to the player (a player on Discord: "are the invasion quests repetitive
 * (always the same opponents)?"; the user: "add more with some variety. Take into consideration the difficulty level,
 * and the players current reputation level").
 * <p>
 * A quests.json template marked {@code "tfrInvasion": true} (read out-of-band by AdventureQuestController, like
 * offerProbability) is adjusted here when it is issued, before its text tokens are filled:
 * <ul>
 *   <li>its minion stage (a mixed Defeat stage) asks for settings {@code invasionMinionsByDifficulty} wins - 2/3/3/4
 *   from Easy to Insane;</li>
 *   <li>its leader is picked near the player's rank ({@link SpawnTierWeighting#effectiveRank}: lifetime wins, a step up
 *   on a long streak) and one rank tougher when the issuing town's reputation reaches
 *   {@code invasionTrustReputation} - the town trusts you with its worst problem. Tagged leaders first; a family with
 *   none within reach sends its strongest member. One of the top two ranks in reach, never the leader this invasion
 *   sent last time when another will do;</li>
 *   <li>the reward follows the leader's rank ({@code invasionGoldByTier}, {@code invasionReputationByTier}), and the
 *   texts read it through $(invasion_count), $(invasion_gold), $(invasion_rep).</li>
 * </ul>
 */
public final class InvasionQuests {
    private InvasionQuests() {
    }

    private static final float[] RANK_STEPS = {0.5f, 1f, 2f, 10f};
    private static final String[] TIERS = {"Common", "Uncommon", "Rare", "Mythic"};

    public static void apply(AdventureQuestData quest) {
        TuningData tuning = Config.instance().getTuningData();
        int diff = Math.max(0, Math.min(3, TerritoryControl.difficultyIndex()));
        int minions = pick(tuning.invasionMinionsByDifficulty, diff, 3);
        int townRep = townReputation(quest.sourceID);
        float rank = SpawnTierWeighting.effectiveRank(Current.player().getStatistic().rank());
        boolean trusted = tuning.invasionTrustReputation > 0 && townRep >= tuning.invasionTrustReputation;
        float allowed = trusted ? stepUp(rank) : rank;
        EnemyData leader = null;
        for (AdventureQuestStage stage : quest.stages) {
            if (stage == null || stage.objective != AdventureQuestController.ObjectiveTypes.Defeat)
                continue;
            if (stage.mixedEnemies) {
                stage.count3 = minions;
                continue;
            }
            String avoid = AdventureQuestController.instance().lastInvasionLeader(quest.getID());
            EnemyData picked = pickLeader(stage, allowed, avoid);
            if (picked != null) {
                stage.setTargetEnemyData(picked);
                quest.putEnemyToken("$(enemy_" + stage.id + ")", picked);
                AdventureQuestController.instance().setLastInvasionLeader(quest.getID(), picked.getName());
            }
            if (stage.getTargetEnemyData() != null)
                leader = stage.getTargetEnemyData();
        }
        int tier = leader == null ? 1 : Math.max(0, Arrays.asList(TIERS).indexOf(leader.tier));
        int gold = pick(tuning.invasionGoldByTier, tier, 500);
        int rep = pick(tuning.invasionReputationByTier, tier, 2);
        if (quest.epilogue != null && quest.epilogue.options != null)
            for (DialogData option : quest.epilogue.options)
                if (option != null && option.action != null)
                    for (DialogData.ActionData action : option.action)
                        if (action != null && action.addGold > 0) {
                            action.addGold = gold;
                            action.addMapReputation = rep;
                        }
        quest.putOtherToken("$(invasion_count)", String.valueOf(minions));
        quest.putOtherToken("$(invasion_gold)", String.valueOf(gold));
        quest.putOtherToken("$(invasion_rep)", String.valueOf(rep));
        System.out.println("[TFR-Invasion] " + quest.name + ": town reputation " + townRep + (trusted ? " (trusted)" : "")
                + ", rank " + rank + " -> leader up to " + allowed + ": "
                + (leader == null ? "none" : leader.getName() + " (" + leader.tier + ")") + ", " + minions
                + " minion win(s), reward " + gold + " gold +" + rep + " reputation");
    }

    private static int townReputation(String sourceID) {
        try {
            if (sourceID == null || sourceID.isEmpty() || WorldSave.getCurrentSave() == null)
                return 0;
            return WorldSave.getCurrentSave().getPointOfInterestChanges(sourceID).getMapReputation();
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private static float stepUp(float rank) {
        for (float step : RANK_STEPS)
            if (step > rank)
                return step;
        return rank;
    }

    private static int pick(int[] table, int index, int fallback) {
        return table != null && index < table.length ? table[index] : fallback;
    }

    /** Tagged leaders within reach, else the family's strongest member within reach; then one of the top two ranks. */
    static EnemyData pickLeader(AdventureQuestStage stage, float allowedRank, String avoid) {
        List<EnemyData> pool = AdventureQuestController.filterQuestSpawnPool(tagScan(stage.enemyTags, stage.enemyExcludeTags),
                allowedRank);
        boolean fallback = false;
        if (pool.isEmpty()) {
            List<String> family = new ArrayList<>(stage.enemyTags);
            family.remove("Leader");
            List<String> exclude = new ArrayList<>(stage.enemyExcludeTags);
            if (!exclude.contains("Boss"))
                exclude.add("Boss");
            pool = AdventureQuestController.filterQuestSpawnPool(tagScan(family, exclude), allowedRank);
            fallback = true;
        }
        if (pool.isEmpty())
            return null;
        TreeSet<Float> ranks = new TreeSet<>();
        for (EnemyData e : pool)
            ranks.add(e.difficulty);
        float floor = fallback || ranks.size() < 2 ? ranks.last() : ranks.lower(ranks.last());
        List<EnemyData> top = new ArrayList<>();
        for (EnemyData e : pool)
            if (e.difficulty >= floor)
                top.add(e);
        if (avoid != null && top.size() > 1)
            top.removeIf(e -> avoid.equals(e.getName()));
        return new EnemyData(Aggregates.random(top));
    }

    private static List<EnemyData> tagScan(List<String> tags, List<String> exclude) {
        List<EnemyData> out = new ArrayList<>();
        for (EnemyData e : WorldData.getAllEnemies()) {
            List<String> have = Arrays.asList(e.questTags);
            if (have.containsAll(tags) && exclude.stream().noneMatch(have::contains))
                out.add(e);
        }
        return out;
    }
}
