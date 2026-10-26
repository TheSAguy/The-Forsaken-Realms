package forge.adventure.player;

import forge.adventure.data.AdventureEventData;
import forge.adventure.util.AdventureQuestController;
import forge.adventure.util.SaveFileContent;
import forge.adventure.util.SaveFileData;
import org.apache.commons.lang3.tuple.Pair;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

public class PlayerStatistic implements SaveFileContent {

    HashMap<String, Pair<Integer,Integer>> winLossRecord=new HashMap<>();
    List<AdventureEventData> completedEvents = new ArrayList<>();
    int secondPlayed=0;
    /** Round 719: every enemy rank the player has started a duel with, as "Name|Tier" ("Bear|Uncommon") - the record
     *  above is by name only, so it cannot tell an Adept Bear from a Master. Read by the Alt info plates (TfrPlates);
     *  saved as "ranksBattled", cleared with the record by New Game and New Game+. An older save loads it empty. */
    HashSet<String> ranksBattled = new HashSet<>();
    /** Round 742 (the user: "facing an opponent in the Arena should not count as the first time you meet them, that should
     *  only be in dungeons and over-world"): of the wins in winLossRecord, how many were arena duels (brackets and the Coin
     *  Challenge), by the same name. The record keeps them - the Statistics screen, rank() and the sell price read it
     *  whole - and fieldWins() takes them out for every "first win" rule. Saved as "arenaWins"; an older save loads it
     *  empty, so the arena wins it already holds stay counted as ordinary wins. */
    HashMap<String, Integer> arenaWins = new HashMap<>();

    public HashMap<String, Pair<Integer,Integer>> getWinLossRecord()
    {
        return winLossRecord;
    }
    public int totalWins()
    {
        int wins=0;
        for(Map.Entry<String, Pair<Integer, Integer>> value:winLossRecord.entrySet())
        {
            wins+=value.getValue().getLeft();
        }
        return wins;
    }
    public float rank() // used for overworld enemy spawn
    {
        int wins=0;
        for(Map.Entry<String, Pair<Integer, Integer>> value:winLossRecord.entrySet())
        {
            wins+=value.getValue().getLeft();
        }
        if (wins < 20) {
            return 0.5f;
        } else if (wins >= 20 && wins < 60) {
            return 1;
        } else if (wins >= 60 && wins <= 150) {
            return 2;
        } else { // wins > 150
            return 10;
        }
    }
    public int totalLoss()
    {
        int loss=0;
        for(Map.Entry<String, Pair<Integer, Integer>> value:winLossRecord.entrySet())
        {
            loss+=value.getValue().getRight();
        }
        return loss;
    }
    public float winLossRatio()
    {
        if (totalLoss() == 0) {
            // Not a true ratio but fixes division by zero
            return totalWins();
        }

        return (float) totalWins()/(float)totalLoss();
    }
    /** Round 213: how many Inn tournaments this character has SEEN THROUGH, win or lose.
     *  <p>
     *  Backs AdventureQuestStage.retroCompleteIfEventAlreadyFinished(): an EventFinish stage only
     *  advances on a live EVENTCOMPLETE, so a tournament played BEFORE the quest was offered could
     *  never tick it. completedEvents is already the persisted, per-character record of exactly
     *  that act (saved and loaded alongside winLossRecord, and cleared by clear() on a new game),
     *  so reading it needs no new state - the same "read persisted state at activation" fix used
     *  for flags and for visited POIs. */
    public int completedEventCount(){
        return completedEvents.size();
    }
    public int eventWins(){
        int win = 0;
        for (AdventureEventData event : completedEvents){
            if (event.playerWon)
                win++;
        }
        return win;
    }
    public int eventLosses(){
        int loss = 0;
        for (AdventureEventData event : completedEvents){
            if (!event.playerWon)
                loss++;
        }
        return loss;
    }
    public float eventWinLossRatio()
    {
        if (eventLosses() == 0) {
            // Not a true ratio but fixes division by zero
            return eventWins();
        }

        return (float) eventWins()/(float)eventLosses();
    }

    public int eventMatchWins(){
        int win = 0;
        for (AdventureEventData event : completedEvents){
            win+= event.matchesWon;
        }
        return win;
    }
    public int eventMatchLosses(){
        int loss = 0;
        for (AdventureEventData event : completedEvents){
            loss += event.matchesLost;
        }
        return loss;
    }
    public float eventMatchWinLossRatio()
    {
        if (eventMatchLosses() == 0) {
            // Not a true ratio but fixes division by zero
            return eventMatchWins();
        }

        return (float) eventMatchWins()/(float)eventMatchLosses();
    }

    public int getPlayTime()
    {
        return secondPlayed;
    }
    @Override
    public void load(SaveFileData data) {

        if(data!=null&&data.containsKey("winLossRecord"))
            winLossRecord = (HashMap<String, Pair<Integer, Integer>>) data.readObject("winLossRecord");
        else
            winLossRecord.clear();

        if (data!=null&&data.containsKey("completedEvents")) {
            completedEvents = (ArrayList<AdventureEventData>) data.readObject("completedEvents");
            if (completedEvents == null) {
                completedEvents = new ArrayList<>();
            }
        }
        else
            completedEvents.clear();

        ranksBattled = new HashSet<>();
        if (data != null && data.containsKey("ranksBattled")) {
            Object stored = data.readObject("ranksBattled");
            if (stored instanceof java.util.Collection)
                for (Object o : (java.util.Collection<?>) stored)
                    if (o instanceof String)
                        ranksBattled.add((String) o);
        }

        arenaWins = new HashMap<>(); // round 742
        if (data != null && data.containsKey("arenaWins")) {
            Object stored = data.readObject("arenaWins");
            if (stored instanceof java.util.Map)
                for (Map.Entry<?, ?> e : ((java.util.Map<?, ?>) stored).entrySet())
                    if (e.getKey() instanceof String && e.getValue() instanceof Integer)
                        arenaWins.put((String) e.getKey(), (Integer) e.getValue());
        }
    }

    /** Round 742: an arena win over this enemy, already counted in the record by DuelScene.recordStatistics(). */
    public void recordArenaWin(String enemy) {
        if (enemy != null)
            arenaWins.merge(enemy, 1, Integer::sum);
    }

    public int arenaWins(String enemy) {
        Integer n = enemy == null ? null : arenaWins.get(enemy);
        return n == null ? 0 : n;
    }

    /** Round 742: the wins over this enemy outside the arena - in the overworld, a dungeon or a town. What every "first
     *  win" rule reads (Ascendance, CardBudget, ResourcePurse). */
    public int fieldWins(String enemy) {
        Pair<Integer, Integer> record = enemy == null ? null : winLossRecord.get(enemy);
        int wins = record == null || record.getLeft() == null ? 0 : record.getLeft();
        return Math.max(0, wins - arenaWins(enemy));
    }

    /** Round 719: the key a rank is kept under. */
    public static String rankKey(String name, String tier) {
        return name + "|" + tier;
    }

    /** Round 719: a duel with this enemy has started (DuelScene.enter: the player's own duels, the Arena's included). */
    public void recordRankBattled(forge.adventure.data.EnemyData enemy) {
        if (enemy == null || enemy.getName() == null)
            return;
        if (ranksBattled.add(rankKey(enemy.getName(), enemy.tier)))
            System.out.println("[TFR-Plates] learned " + enemy.getName() + " (" + forge.adventure.data.EnemyData.tierDisplayName(enemy.tier)
                    + ") - " + ranksBattled.size() + " rank(s) battled");
    }

    public boolean hasBattledRank(String name, String tier) {
        return ranksBattled.contains(rankKey(name, tier));
    }

    public java.util.Set<String> getRanksBattled() {
        return ranksBattled;
    }

    public void setResult(String enemy,boolean win)
    {
        if(!winLossRecord.containsKey(enemy))
        {
            if(win)
                winLossRecord.put(enemy,Pair.of(1,0));
            else
                winLossRecord.put(enemy,Pair.of(0,1));
        }
        else
        {

            if(win)
                winLossRecord.put(enemy,Pair.of(winLossRecord.get(enemy).getLeft()+1,winLossRecord.get(enemy).getRight()));
            else
                winLossRecord.put(enemy,Pair.of(winLossRecord.get(enemy).getLeft(),winLossRecord.get(enemy).getRight()+1));
        }
    }


    @Override
    public SaveFileData save() {

        SaveFileData data=new SaveFileData();
        data.storeObject("winLossRecord",winLossRecord);
        data.storeObject("completedEvents", completedEvents);
        data.storeObject("ranksBattled", new ArrayList<>(ranksBattled)); // round 719
        data.storeObject("arenaWins", new HashMap<>(arenaWins)); // round 742
        return data;
    }


    public void clear() {
        winLossRecord.clear();
        ranksBattled.clear(); // round 719: New Game and New Game+ start knowing no enemy
        arenaWins.clear(); // round 742
        // completedEvents was NOT cleared here (fixed 2026-08-29, user request that a New Game /
        // New Game+ reset these values). It is persisted by save() and restored by load() just
        // like winLossRecord, and it backs every event statistic on the Statistics screen
        // (eventWins/eventLosses/eventMatchWins/eventMatchLosses). Because a new game reuses the
        // static WorldSave's final AdventurePlayer - and therefore THIS same PlayerStatistic
        // instance (see WorldSave.generateNewWorld -> player.create -> AdventurePlayer.clear) -
        // a fresh character inherited the previous run's entire tournament history while its
        // per-enemy record correctly started empty. Matters more since tournament results are now
        // tracked ONLY here (see DuelScene.afterGameEnd).
        completedEvents.clear();
    }

    public void setResult(AdventureEventData completedEvent) {
        completedEvents.add(completedEvent);
        // Round 639: a finished tournament spends the one-time first-tournament Coin net for good - the flag outlives
        // a New Game+, where this list is cleared (EventScene.firstTournamentNetOpen).
        if (forge.adventure.util.Current.player() != null)
            forge.adventure.util.Current.player().setCharacterFlag(AdventurePlayer.INN_TOURNAMENT_QUEST_FLAG, 1);
        AdventureQuestController.instance().updateEventComplete(completedEvent);
    }
}
