package forge.adventure.util;

import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.utils.Json;
import forge.adventure.character.EnemySprite;
import forge.adventure.data.AdventureEventData;
import forge.adventure.data.AdventureQuestData;
import forge.adventure.data.AscendanceData;
import forge.adventure.data.AscendanceState;
import forge.adventure.data.ConfigData;
import forge.adventure.data.EnemyData;
import forge.adventure.data.ItemData;
import forge.adventure.data.RoamingGuardData;
import forge.adventure.player.AdventurePlayer;
import forge.adventure.pointofintrest.PointOfInterest;
import forge.adventure.stage.GameHUD;
import forge.adventure.stage.WorldStage;
import forge.adventure.world.WorldSave;
import org.apache.commons.lang3.tuple.Pair;

import java.util.ArrayList;
import java.util.List;

/**
 * Round 493 - Ascendance, the player's level. Design: docs/design/2026-10-08-xp-leveling.md, section 7 (the user picked
 * Option B with changes: "Let's try out option B. I do want to add one thing though. Equipment slots should also be
 * gated behind leveling. You start with 1 slot ... Let's actually combine +1 life and Equipment slot unlock at 5, 10,
 * etc." and "Let's say it won't work on existing saves, have to do a NG+ or new game").
 * <ul>
 * <li><b>Power</b> (XP) comes from won duels - by the enemy's rank, times the highest multiplier that applies (boss,
 * legend, first win against that enemy, town fight, treasure guardian, champion, attacking mage), less for an enemy the
 * player has outgrown - and from quests, first visits, clears, restored and captured towns, the Capitol, a stopped
 * pillage, an Arena bracket, an Inn tournament and a treasure. Never from a loss, a guard's fight, Deck Tester or
 * anything inside a duel.</li>
 * <li><b>Levels</b> 1-30 on the config table's curve. Every 5th level: +1 max life (milestoneLife) and a title; 5, 10, 15
 * and 20 also allow one more <b>main-slot item</b> (Neck, Body, Left, Right, Boots - the gauntlets' Left2/Right2 are a
 * bonus and never count). Every other level: a pick-1-of-3 reward (pendingChoices; the choices themselves are the next
 * round). Past 30 there is no cap (the user: "make the leveling MUCH slower after 30 and give +1 life for each level"):
 * postCapXpToNext per level, rising, +postCapLife each. levelingSpeed scales every award.</li>
 * <li>The player can switch all of it off in Settings (SettingData.ascendanceDisabled).</li>
 * <li><b>Roaming guards</b> wear main-slot items by rank: Apprentice 2, Adept 3, Master 4, Archmage 5.</li>
 * <li>Only a character started by New Game or New Game+ has it (AscendanceState.on). An older save plays as before.</li>
 * </ul>
 * Every number is in "config tables/ascendance.json" (AscendanceData). [TFR-Ascend] logs every award and level.
 */
public final class Ascendance {
    private Ascendance() {
    }

    /** The five main equipment slots the level limits (the user: "just the 5 main slots: Neck, Chest, Left Hand, Right
     *  Hand and Boots"). Left2/Right2, granted by a gauntlet, are not among them. */
    public static final String[] MAIN_SLOTS = {"Neck", "Body", "Left", "Right", "Boots"};

    private static AscendanceData data;

    // ------------------------------------------------------------------------------------------------ config and state

    public static AscendanceData data() {
        if (data == null) {
            FileHandle file = Config.instance().getFile("config tables/ascendance.json");
            try {
                data = file != null && file.exists() ? new Json().fromJson(AscendanceData.class, file) : new AscendanceData();
            } catch (Exception e) {
                System.err.println("[TFR-Ascend] ascendance.json failed to load - Ascendance stays off: " + e);
                data = new AscendanceData();
            }
        }
        return data;
    }

    /** The plane switch (config.json ascendanceEnabled) - read when a run starts, not by a running save. */
    public static boolean enabledForNewRuns() {
        ConfigData config = Config.instance().getConfigData();
        return config != null && config.ascendanceEnabled && data().xpToNext.length > 0;
    }

    private static AscendanceState state() {
        AdventurePlayer player = WorldSave.getCurrentSave() == null ? null : WorldSave.getCurrentSave().getPlayer();
        return player == null ? null : player.ascendance();
    }

    /** The player's own switch (Settings, "Leveling (Ascendance)") - the user: "I do think the XP/Leveling system is
     *  going to be controversial, so maybe add an option is settings to turn it all off". */
    public static boolean switchedOffInSettings() {
        forge.adventure.data.SettingData settings = Config.instance().getSettingData();
        return settings != null && settings.ascendanceDisabled;
    }

    /** Does the current character have Ascendance (started by a New Game or New Game+ with it on), and has the player
     *  not switched it off in Settings? Off means all of it: no Power, no main-slot limit, no guard limit, no HUD. What
     *  was already gained stays, and switching it back on carries on from there. */
    public static boolean isActive() {
        AscendanceState s = state();
        return s != null && s.on && data().xpToNext.length > 0 && !switchedOffInSettings();
    }

    public static int power() {
        AscendanceState s = state();
        return s == null ? 0 : s.power;
    }

    public static int pendingChoices() {
        AscendanceState s = state();
        return s == null ? 0 : s.pendingChoices;
    }

    /** The last level on the curve (30); past it the levels go on, much slower (postCapXpToNext). */
    public static int curveTop() {
        return data().xpToNext.length + 1;
    }

    /** Power to go from {@code level} to the next - the curve, then the slow post-30 levels. */
    public static int powerToNext(int level) {
        AscendanceData d = data();
        if (level - 1 < d.xpToNext.length)
            return d.xpToNext[level - 1];
        return Math.max(1, d.postCapXpToNext + d.postCapXpStep * (level - curveTop()));
    }

    public static int levelFor(int power) {
        int level = 1;
        int spent = 0;
        while (level < 999) {
            int need = powerToNext(level);
            if (power < spent + need)
                return level;
            spent += need;
            level++;
        }
        return level;
    }

    public static int level() {
        return levelFor(power());
    }

    /** Power the given level starts at. */
    private static int powerAtLevel(int level) {
        int total = 0;
        for (int l = 1; l < level; l++)
            total += powerToNext(l);
        return total;
    }

    /** [into this level, needed for the next] - the HUD bar. */
    public static int[] progress() {
        int level = level();
        return new int[]{power() - powerAtLevel(level), powerToNext(level)};
    }

    /** The title earned so far, or "" before the first. */
    public static String title() {
        return titleAt(level());
    }

    private static String titleAt(int level) {
        AscendanceData d = data();
        String title = "";
        for (int i = 0; i < Math.min(d.titleLevels.length, d.titles.length); i++)
            if (level >= d.titleLevels[i])
                title = d.titles[i];
        return title;
    }

    private static boolean contains(int[] levels, int level) {
        for (int l : levels)
            if (l == level)
                return true;
        return false;
    }

    // ------------------------------------------------------------------------------------------------ main-slot limit

    public static boolean isMainSlot(String slot) {
        for (String s : MAIN_SLOTS)
            if (s.equals(slot))
                return true;
        return false;
    }

    /** How many main-slot items the player may wear at this level. */
    public static int mainSlotAllowance(int level) {
        AscendanceData d = data();
        int allowed = d.mainSlotsBase;
        for (int l : d.mainSlotLevels)
            if (level >= l)
                allowed++;
        return Math.min(MAIN_SLOTS.length, allowed);
    }

    public static int mainSlotAllowance() {
        return mainSlotAllowance(level());
    }

    /** The next level that allows one more main-slot item, or -1 when all five are open. */
    public static int nextMainSlotLevel() {
        int level = level();
        for (int l : data().mainSlotLevels)
            if (l > level)
                return l;
        return -1;
    }

    public static int mainItemsWorn(AdventurePlayer player) {
        int worn = 0;
        for (String slot : MAIN_SLOTS)
            if (player.itemInSlot(slot) != null)
                worn++;
        return worn;
    }

    /**
     * Why this item may not go into {@code slot} now, or null when it may. Only a main slot that is EMPTY counts against
     * the limit - swapping the item in a slot already worn never changes the count.
     */
    public static String equipRefusal(AdventurePlayer player, ItemData item, String slot) {
        if (!isActive() || item == null || !isMainSlot(slot) || player.itemInSlot(slot) != null)
            return null;
        int allowed = mainSlotAllowance();
        if (mainItemsWorn(player) < allowed)
            return null;
        int next = nextMainSlotLevel();
        return "Your power allows " + allowed + " main item" + (allowed == 1 ? "" : "s")
                + " (neck, body, hands, boots)" + (next > 0 ? " - Ascendance " + next + " brings one more." : ".");
    }

    /** The refusal for an item that equip() would not put on - for the screens' message. */
    public static String refusalFor(AdventurePlayer player, ItemData item) {
        String why = item == null ? null : equipRefusal(player, item, item.equipmentSlot);
        return why != null ? why : "Your power allows no more main items yet.";
    }

    /** "Main items 1 / 2" for the inventory and Armory screens; "" when the character has no Ascendance. */
    public static String mainItemsLabel(AdventurePlayer player) {
        if (!isActive())
            return "";
        return "Main items " + mainItemsWorn(player) + " / " + mainSlotAllowance();
    }

    /** A line under a main-slot item's description: how many are worn of how many allowed, and when one more comes. */
    public static String mainItemsLine(AdventurePlayer player, ItemData item) {
        if (!isActive() || item == null || !isMainSlot(item.equipmentSlot))
            return "";
        int next = nextMainSlotLevel();
        return "\n[%85]" + mainItemsLabel(player) + (next > 0 ? " - one more at Ascendance " + next : "");
    }

    /** Take main-slot items off, last slot first, until the count fits the allowance (New Game+, a deck's loadout). */
    public static void enforceMainLimit(AdventurePlayer player, String why) {
        if (!isActive())
            return;
        int allowed = mainSlotAllowance();
        List<String> removed = new ArrayList<>();
        for (int i = MAIN_SLOTS.length - 1; i >= 0 && mainItemsWorn(player) > allowed; i--) {
            String name = player.takeOffSlot(MAIN_SLOTS[i]);
            if (name != null)
                removed.add(name);
        }
        if (removed.isEmpty())
            return;
        System.out.println("[TFR-Ascend] " + why + ": " + removed.size() + " main item(s) taken off to fit " + allowed
                + " at Ascendance " + level() + " - " + removed);
        GameHUD.getInstance().addNotification("Your power allows " + allowed + " main item" + (allowed == 1 ? "" : "s")
                + " - " + String.join(", ", removed) + " went back in the bag.");
    }

    // ------------------------------------------------------------------------------------------------ roaming guards

    /** The user: "Let's limit the roaming guards equipment slots also. Apprentice - 2, Adept - 3, Master - 4 and Archmage 5." */
    public static int guardMainAllowance(String tier) {
        int[] byRank = data().guardMainSlots;
        int rank = Math.max(0, EnemyData.tierRank(tier));
        return rank < byRank.length ? byRank[rank] : MAIN_SLOTS.length;
    }

    public static int guardMainWorn(RoamingGuardData guard) {
        int worn = 0;
        for (ItemData item : guard.equipment)
            if (item != null && isMainSlot(item.equipmentSlot))
                worn++;
        return worn;
    }

    /** Why the guard may not put this item on now, or null. Replacing what it wears in that slot is always allowed. */
    public static String guardRefusal(RoamingGuardData guard, ItemData item) {
        if (!isActive() || item == null || !isMainSlot(item.equipmentSlot))
            return null;
        for (ItemData worn : guard.equipment)
            if (worn != null && item.equipmentSlot.equals(worn.equipmentSlot))
                return null;
        int allowed = guardMainAllowance(guard.tier);
        if (guardMainWorn(guard) < allowed)
            return null;
        String rank = RoamingGuards.displayName(guard.tier);
        return ("AEIOU".indexOf(rank.charAt(0)) >= 0 ? "An " : "A ") + rank + " guard wears " + allowed
                + " main items at most - a higher rank wears more.";
    }

    /** A guard lowered in rank keeps only what the new rank allows; the rest goes back to the Armory storage. */
    public static void enforceGuardLimit(RoamingGuardData guard) {
        if (!isActive())
            return;
        int allowed = guardMainAllowance(guard.tier);
        for (int i = MAIN_SLOTS.length - 1; i >= 0 && guardMainWorn(guard) > allowed; i--)
            for (ItemData item : new ArrayList<>(guard.equipment))
                if (item != null && MAIN_SLOTS[i].equals(item.equipmentSlot)) {
                    ArmoryStorage.takeFromGuard(guard, item);
                    System.out.println("[TFR-Ascend] " + RoamingGuards.displayName(guard.tier) + " guard wears " + allowed
                            + " main items - " + item.name + " back to the storage");
                }
    }

    // ------------------------------------------------------------------------------------------------ Power

    /** A new character (New Game) or a New Game+: back to Ascendance 1, on when the plane has it on. */
    public static void startRun(AdventurePlayer player, String why) {
        AscendanceState s = player.ascendance();
        s.reset();
        s.on = enabledForNewRuns();
        System.out.println("[TFR-Ascend] " + why + ": Ascendance " + (s.on ? "on - level 1, " + mainSlotAllowance(1)
                + " main item(s)" : "off (config.json ascendanceEnabled)"));
    }

    /** Award Power for {@code source} (times the config's levelingSpeed); level-ups pay their rewards at once. */
    public static void award(int base, String source) {
        AscendanceState s = state();
        if (s == null || !isActive() || base <= 0)
            return;
        int amount = Math.max(1, Math.round(base * Math.max(0f, data().levelingSpeed)));
        int before = levelFor(s.power);
        s.power += amount;
        int after = levelFor(s.power);
        System.out.println("[TFR-Ascend] +" + amount + " Power - " + source + " (total " + s.power + ", level " + after + ")");
        GameHUD.getInstance().addNotification("[%85]+" + amount + " Power - " + source);
        for (int level = before + 1; level <= after; level++)
            onLevelUp(level);
    }

    private static void onLevelUp(int level) {
        AscendanceData d = data();
        AscendanceState s = state();
        AdventurePlayer player = WorldSave.getCurrentSave().getPlayer();
        List<String> gifts = new ArrayList<>();
        boolean pastCurve = level > curveTop();
        int life = pastCurve ? d.postCapLife : contains(d.lifeLevels, level) ? d.milestoneLife : 0;
        if (life > 0) {
            player.addMaxLife(life);
            gifts.add("+" + life + " max life");
        }
        if (contains(d.mainSlotLevels, level))
            gifts.add(mainSlotAllowance(level) + " main items");
        String title = contains(d.titleLevels, level) ? titleAt(level) : "";
        if (gifts.isEmpty() && title.isEmpty() && !pastCurve) {
            s.pendingChoices++;
            gifts.add("a reward to choose");
        }
        System.out.println("[TFR-Ascend] LEVEL " + level + (title.isEmpty() ? "" : " - " + title) + ": "
                + String.join(", ", gifts) + " (choices waiting: " + s.pendingChoices + ")");
        GameHUD.getInstance().addNotification("[GOLD]Ascendance " + level + (title.isEmpty() ? "" : " - " + title) + "![] "
                + capitalize(String.join(", ", gifts)) + ".", true);
    }

    private static String capitalize(String text) {
        return text.isEmpty() ? text : Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    // ------------------------------------------------------------------------------------------------ the sources

    /**
     * DuelScene.afterGameEnd, a won duel - the player's own (not a guard's fight, an Inn match or Deck Tester; the
     * caller checks). {@code enemyName} is the win/loss record's key, read before this win is written to it.
     */
    public static void onDuelWon(EnemySprite enemy, String enemyName, boolean arena) {
        if (!isActive() || enemy == null || enemy.getData() == null)
            return;
        AscendanceData d = data();
        EnemyData e = enemy.getData();
        int rank = Math.max(0, Math.min(d.duelBase.length - 1, EnemyData.tierRank(e.tier)));
        float factor = 1f;
        String why = "";
        Pair<Integer, Integer> record = WorldSave.getCurrentSave().getPlayer().getStatistic().getWinLossRecord().get(enemyName);
        boolean firstWin = record == null || record.getLeft() == 0;
        if (e.boss && d.bossFactor > factor) { factor = d.bossFactor; why = "boss"; }
        if ((e.legend || LegendSpawns.isMember(e)) && d.legendFactor > factor) { factor = d.legendFactor; why = "legend"; }
        if (firstWin && d.firstWinFactor > factor) { factor = d.firstWinFactor; why = "first win"; }
        if (!arena && WorldStage.getInstance().isTownOrCapitolFight() && d.townFightFactor > factor) { factor = d.townFightFactor; why = "town fight"; }
        if (!arena && WorldStage.getInstance().isTreasureGuardianFight() && d.treasureGuardianFactor > factor) { factor = d.treasureGuardianFactor; why = "treasure guardian"; }
        if ((enemy.championLoot || WarChampions.isWarChampion(e.getName())) && d.championFactor > factor) { factor = d.championFactor; why = "champion"; }
        if (enemy.territoryColor != null && d.mageFactor > factor) { factor = d.mageFactor; why = "attacking mage"; }
        // Outgrown: above the rank's band top, 10% less per level, never under the floor.
        int level = level();
        int top = rank < d.outgrownBandTop.length ? d.outgrownBandTop[rank] : 999;
        float outgrown = level > top ? Math.max(d.outgrownFloor, 1f - d.outgrownStep * (level - top)) : 1f;
        int amount = Math.max(1, Math.round(d.duelBase[rank] * factor * outgrown));
        award(amount, "beat " + e.getName() + " (" + EnemyData.tierDisplayName(e.tier) + (why.isEmpty() ? "" : ", " + why)
                + (outgrown < 1f ? ", outgrown " + Math.round(outgrown * 100) + "%" : "") + ")");
    }

    /** AdventureQuestController.showQuestDialogs: a quest done. An invasion pays by the toughest troop it lost. */
    public static void onQuestCompleted(AdventureQuestData quest) {
        if (!isActive() || quest == null)
            return;
        AscendanceData d = data();
        if (InvasionQuests.isInvasion(quest)) {
            int rank = Math.max(0, Math.min(d.invasion.length - 1, InvasionQuests.toughestTroopRank(quest)));
            award(d.invasion[rank], "invasion repelled");
        } else if (quest.storyQuest) {
            award(d.storyQuest, "story: " + quest.name);
        } else {
            award(d.sideQuest, "quest: " + quest.name);
        }
    }

    /** WorldStage.handlePointsOfInterestCollision: walking into a place for the first time. */
    public static void onFirstVisit(PointOfInterest poi) {
        if (!isActive() || poi == null || poi.getData() == null)
            return;
        String type = poi.getData().type;
        boolean town = "town".equals(type) || "capital".equals(type);
        award(town ? data().firstVisitTown : data().firstVisitPlace, "first visit: " + poi.getDisplayName());
    }

    /** DungeonSources.onCleared: a dungeon or cave cleared (once per incarnation). */
    public static void onPlaceCleared(PointOfInterest poi) {
        if (!isActive() || poi == null || poi.getData() == null)
            return;
        boolean cave = "cave".equals(poi.getData().type);
        award(cave ? data().clearCave : data().clearDungeon, "cleared " + poi.getDisplayName());
    }

    public static void onTownRestored(String town) {
        if (isActive())
            award(data().restoreTown, "restored " + town);
    }

    public static void onTownCaptured(String town) {
        if (isActive())
            award(data().captureTown, "captured " + town);
    }

    public static void onCapitolRaised() {
        if (isActive())
            award(data().raiseCapitol, "the Capitol raised");
    }

    public static void onPillageStopped(String town) {
        if (isActive())
            award(data().pillageStopped, "the raiders driven from " + town);
    }

    public static void onTreasureFound(String region) {
        if (isActive())
            award(data().treasureFound, "the " + region + " treasure");
    }

    public static void onArenaBracketWon() {
        if (isActive())
            award(data().arenaBracket, "Arena bracket won");
    }

    /** AdventureEventController.finalizeEvent: an Inn tournament over - per match won, and more for winning it. */
    public static void onInnEventEnded(AdventureEventData event) {
        if (!isActive() || event == null)
            return;
        int amount = event.matchesWon * data().innMatchWin + (event.matchesLost == 0 && event.matchesWon > 0 ? data().innChampion : 0);
        award(amount, "Inn tournament (" + event.matchesWon + " match" + (event.matchesWon == 1 ? "" : "es") + " won)");
    }

    // ------------------------------------------------------------------------------------------------ save

    /** AdventurePlayer.save: the state under its own keys (no serialized object - see AscendanceState). */
    public static void save(forge.adventure.util.SaveFileData data, AscendanceState s) {
        data.store("ascendanceOn", s.on);
        data.store("ascendancePower", s.power);
        data.store("ascendancePending", s.pendingChoices);
    }

    /** AdventurePlayer.load: absent keys (every save before round 493) read as "off". */
    public static void load(forge.adventure.util.SaveFileData data, AscendanceState s) {
        s.reset();
        s.on = data.containsKey("ascendanceOn") && data.readBool("ascendanceOn");
        s.power = data.containsKey("ascendancePower") ? Math.max(0, data.readInt("ascendancePower")) : 0;
        s.pendingChoices = data.containsKey("ascendancePending") ? Math.max(0, data.readInt("ascendancePending")) : 0;
    }

    // ------------------------------------------------------------------------------------------------ cheats

    public static String cheatGive(int amount) {
        if (!isActive())
            return "This character has no Ascendance (New Game or New Game+ only)";
        award(amount, "console");
        return "Ascendance " + level() + ", " + power() + " Power";
    }

    public static String cheatSetLevel(int target) {
        AscendanceState s = state();
        if (s == null || !s.on)
            return "This character has no Ascendance (New Game or New Game+ only)";
        int want = Math.max(1, Math.min(200, target));
        int need = powerAtLevel(want) - s.power;
        if (need > 0) { // straight to the target Power - levelingSpeed must not scale a console jump
            int before = levelFor(s.power);
            s.power += need;
            System.out.println("[TFR-Ascend] console: +" + need + " Power (total " + s.power + ")");
            for (int level = before + 1; level <= levelFor(s.power); level++)
                onLevelUp(level);
        }
        return "Ascendance " + level() + ", " + power() + " Power";
    }

    public static String cheatInfo() {
        AscendanceState s = state();
        if (s == null || !s.on)
            return "Ascendance off for this character";
        int[] p = progress();
        AdventurePlayer player = WorldSave.getCurrentSave().getPlayer();
        return "Ascendance " + level() + (title().isEmpty() ? "" : " (" + title() + ")") + ", " + s.power + " Power, "
                + p[0] + "/" + p[1] + " to next; main items " + mainItemsWorn(player) + "/" + mainSlotAllowance()
                + "; choices waiting " + s.pendingChoices + "; max life " + player.getMaxLife();
    }
}
