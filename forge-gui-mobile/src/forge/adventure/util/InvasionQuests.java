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
 * Round 481 (the user: "For invasion quests, let's have the leader you have to kill at the end always be 1 level higher
 * than the troops you killed before him. On Hard and Insane, he should start with a Gemstone Mine in play"): the
 * leader picked at issue is a stand-in. Every troop win records the toughest rank beaten ($(invasion_troop_rank), in
 * the quest's saved tokens), and when the leader stage opens its leader is re-picked one rank above it (Archmage at
 * most), the reward and the epilogue's reward line following him. On Hard and Insane he starts with
 * {@code invasionLeaderStartCards} in play (DuelScene).
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
        setRewardActions(quest, gold, rep);
        quest.putOtherToken(COUNT, String.valueOf(minions));
        quest.putOtherToken("$(invasion_gold)", String.valueOf(gold));
        quest.putOtherToken("$(invasion_rep)", String.valueOf(rep));
        System.out.println("[TFR-Invasion] " + quest.name + ": town reputation " + townRep + (trusted ? " (trusted)" : "")
                + ", rank " + rank + " -> leader up to " + allowed + ": "
                + (leader == null ? "none" : leader.getName() + " (" + leader.tier + ")") + ", " + minions
                + " minion win(s), reward " + gold + " gold +" + rep + " reputation");
    }

    private static final String COUNT = "$(invasion_count)";
    private static final String TROOP_RANK = "$(invasion_troop_rank)";

    private static void setRewardActions(AdventureQuestData quest, int gold, int rep) {
        if (quest.epilogue != null && quest.epilogue.options != null)
            for (DialogData option : quest.epilogue.options)
                if (option != null && option.action != null)
                    for (DialogData.ActionData action : option.action)
                        if (action != null && action.addGold > 0) {
                            action.addGold = gold;
                            action.addMapReputation = rep;
                        }
    }

    /** An issued invasion: its $(invasion_count) token is put by apply() and saved with the quest. */
    public static boolean isInvasion(AdventureQuestData quest) {
        return quest != null && quest.getOtherToken(COUNT) != null;
    }

    /** Round 493: the toughest troop rank an invasion lost, for Ascendance's Power (0 when none was counted). */
    public static int toughestTroopRank(AdventureQuestData quest) {
        return Math.max(0, troopRank(quest));
    }

    /** The toughest troop rank this invasion has lost (0 Apprentice .. 3 Archmage), -1 before the first. */
    private static int troopRank(AdventureQuestData quest) {
        try {
            String value = quest.getOtherToken(TROOP_RANK);
            return value == null ? -1 : Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** Round 481: a troop win counted by the invasion's mixed stage - keep the toughest rank. */
    public static void noteTroopBeaten(AdventureQuestData quest, AdventureQuestStage stage, AdventureQuestEvent event) {
        if (!isInvasion(quest) || !stage.mixedEnemies || stage.objective != AdventureQuestController.ObjectiveTypes.Defeat
                || event == null || event.enemy == null || event.enemy.getData() == null)
            return;
        int rank = EnemyData.tierRank(event.enemy.getData().tier);
        int had = troopRank(quest);
        if (rank > had)
            quest.putOtherToken(TROOP_RANK, String.valueOf(rank));
        System.out.println("[TFR-Invasion] " + quest.name + ": troop " + event.enemy.getData().getName() + " ("
                + event.enemy.getData().tier + ") beaten - toughest so far " + TIERS[Math.max(rank, had)]);
    }

    /**
     * Round 481: the leader stage opens - its leader one rank above the toughest troop beaten (Archmage at most), the
     * reward following him. No troop on record (a quest issued before round 481) keeps the leader it was issued with.
     */
    public static void onStageActivated(AdventureQuestData quest, AdventureQuestStage stage) {
        if (!isInvasion(quest) || stage.mixedEnemies || stage.objective != AdventureQuestController.ObjectiveTypes.Defeat)
            return;
        int troops = troopRank(quest);
        if (troops < 0)
            return;
        int want = Math.min(3, troops + 1);
        EnemyData before = stage.getTargetEnemyData();
        EnemyData leader = before;
        if (before == null || EnemyData.tierRank(before.tier) != want) {
            String avoid = AdventureQuestController.instance().lastInvasionLeader(quest.getID());
            EnemyData picked = pickLeaderAtRank(stage, want, avoid);
            if (picked != null) {
                leader = picked;
                stage.setTargetEnemyData(picked);
                quest.putEnemyToken("$(enemy_" + stage.id + ")", picked);
                AdventureQuestController.instance().setLastInvasionLeader(quest.getID(), picked.getName());
            }
        }
        if (leader == null)
            return;
        TuningData tuning = Config.instance().getTuningData();
        int tier = EnemyData.tierRank(leader.tier);
        int gold = pick(tuning.invasionGoldByTier, tier, 500);
        int rep = pick(tuning.invasionReputationByTier, tier, 2);
        boolean rewardMoved = moveRewardLine(quest, gold, rep);
        System.out.println("[TFR-Invasion] " + quest.name + ": troops up to " + TIERS[troops] + " -> leader "
                + leader.getName() + " (" + leader.tier + ")" + (leader == before ? " (as issued)" : " (was "
                + (before == null ? "none" : before.getName() + " (" + before.tier + ")") + ")") + ", reward "
                + (rewardMoved ? gold + " gold +" + rep + " reputation" : "as issued"));
    }

    /** The epilogue's reward line was filled at issue ("(+2 Local Reputation, +500 [+Gold])"): rewrite it and the paid
     *  amounts together, or neither when the line is not there (a hand-written epilogue keeps what it promised). */
    private static boolean moveRewardLine(AdventureQuestData quest, int gold, int rep) {
        String oldGold = quest.getOtherToken("$(invasion_gold)"), oldRep = quest.getOtherToken("$(invasion_rep)");
        if (quest.epilogue == null || quest.epilogue.text == null || oldGold == null || oldRep == null)
            return false;
        String was = "+" + oldRep + " Local Reputation, +" + oldGold + " [+Gold]";
        if (!quest.epilogue.text.contains(was))
            return false;
        quest.epilogue.text = quest.epilogue.text.replace(was, "+" + rep + " Local Reputation, +" + gold + " [+Gold]");
        setRewardActions(quest, gold, rep);
        quest.putOtherToken("$(invasion_gold)", String.valueOf(gold));
        quest.putOtherToken("$(invasion_rep)", String.valueOf(rep));
        return true;
    }

    /** Round 481: a leader of exactly this rank - Leader-tagged first, then the family's members (no bosses); with
     *  nobody at that rank, the nearest rank, the tougher side first. Not limited by the player's own rank: the leader
     *  is "always 1 level higher" than the troops. */
    static EnemyData pickLeaderAtRank(AdventureQuestStage stage, int want, String avoid) {
        List<String> family = new ArrayList<>(stage.enemyTags);
        family.remove("Leader");
        List<String> exclude = new ArrayList<>(stage.enemyExcludeTags);
        if (!exclude.contains("Boss"))
            exclude.add("Boss");
        List<EnemyData> leaders = usable(tagScan(stage.enemyTags, stage.enemyExcludeTags));
        List<EnemyData> members = usable(tagScan(family, exclude));
        for (List<EnemyData> pool : Arrays.asList(leaders, members)) {
            List<EnemyData> at = atRank(pool, want);
            if (!at.isEmpty())
                return choose(at, avoid);
        }
        List<EnemyData> any = leaders.isEmpty() ? members : leaders;
        int best = -1, bestDistance = Integer.MAX_VALUE;
        for (EnemyData e : any) {
            int rank = EnemyData.tierRank(e.tier);
            int distance = Math.abs(rank - want) * 2 + (rank < want ? 1 : 0);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = rank;
            }
        }
        return best < 0 ? null : choose(atRank(any, best), avoid);
    }

    private static List<EnemyData> usable(List<EnemyData> in) {
        List<EnemyData> out = new ArrayList<>();
        for (EnemyData e : in)
            if (!SpawnTierWeighting.isExempt(e) && ContentFilterTables.isEnemyIncluded(e.getName()))
                out.add(e);
        return out;
    }

    private static List<EnemyData> atRank(List<EnemyData> pool, int rank) {
        List<EnemyData> out = new ArrayList<>();
        for (EnemyData e : pool)
            if (EnemyData.tierRank(e.tier) == rank)
                out.add(e);
        return out;
    }

    private static EnemyData choose(List<EnemyData> pool, String avoid) {
        List<EnemyData> top = new ArrayList<>(pool);
        if (avoid != null && top.size() > 1)
            top.removeIf(e -> avoid.equals(e.getName()));
        return new EnemyData(Aggregates.random(top));
    }

    /** Round 481: whether this duel's enemy is the leader of an open invasion on Hard or Insane - he starts with
     *  invasionLeaderStartCards in play. Matched by name, as the leader stage itself counts him. */
    public static String[] leaderStartCards(EnemyData enemy) {
        TuningData tuning = Config.instance().getTuningData();
        if (enemy == null || tuning == null || tuning.invasionLeaderStartCards == null
                || tuning.invasionLeaderStartCards.length == 0
                || TerritoryControl.difficultyIndex() < tuning.invasionLeaderStartMinDifficulty)
            return null;
        for (AdventureQuestData quest : Current.player().getQuests()) {
            if (!isInvasion(quest))
                continue;
            for (AdventureQuestStage stage : quest.stages)
                if (stage != null && !stage.mixedEnemies && stage.objective == AdventureQuestController.ObjectiveTypes.Defeat
                        && stage.getStatus() == AdventureQuestController.QuestStatus.ACTIVE
                        && stage.getTargetEnemyData() != null && enemy.getName().equals(stage.getTargetEnemyData().getName()))
                    return tuning.invasionLeaderStartCards;
        }
        return null;
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
