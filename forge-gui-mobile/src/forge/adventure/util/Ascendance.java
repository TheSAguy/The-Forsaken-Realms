package forge.adventure.util;

import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.utils.Json;
import forge.adventure.character.EnemySprite;
import forge.adventure.data.AdventureEventData;
import forge.adventure.data.AdventureQuestData;
import forge.adventure.data.AscendanceData;
import forge.adventure.data.AscendanceState;
import forge.adventure.data.ConfigData;
import forge.adventure.data.EffectData;
import forge.adventure.data.EnemyData;
import forge.adventure.data.ItemData;
import forge.adventure.data.RoamingGuardData;
import forge.adventure.player.AdventurePlayer;
import forge.adventure.pointofintrest.PointOfInterest;
import forge.adventure.stage.GameHUD;
import forge.adventure.stage.WorldStage;
import forge.adventure.world.WorldSave;
import forge.item.IPaperCard;
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
 * <li><b>Companions</b> (round 502), items that start a creature on the battlefield, in any slot: 1, then 2 at 15 and 3 at
 * 25 - and companionsWithoutAscendance for a character without it.</li>
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
        return s == null ? 0 : s.pendingLevels.size();
    }

    /** The last level on the curve (30); past it the levels go on, much slower (postCapXpToNext). Round 497 (the user:
     *  "let's start at level 0"): a character starts at level 0 and xpToNext[0] is the step 0 -> 1. */
    public static int curveTop() {
        return data().xpToNext.length;
    }

    /** Power to go from {@code level} to the next on this character's difficulty - the curve, then the slow post-30
     *  levels. */
    public static int powerToNext(int level) {
        return powerToNext(level, costFactor());
    }

    /** {@code factor} (difficultyLevelCost) scales every level from 1 on, rounded to 5; the step 0 -> 1 never. */
    static int powerToNext(int level, float factor) {
        AscendanceData d = data();
        int raw = level >= 0 && level < d.xpToNext.length ? d.xpToNext[level]
                : Math.max(1, d.postCapXpToNext + d.postCapXpStep * (level - curveTop()));
        return level < 1 || factor == 1f ? raw : Math.max(5, Math.round(raw * factor / 5f) * 5);
    }

    /** Round 497: the difficulty's level cost (difficultyLevelCost) - 1 for a name the table does not list. */
    static float costFactor(String difficulty) {
        AscendanceData d = data();
        for (int i = 0; i < Math.min(d.difficultyNames.length, d.difficultyLevelCost.length); i++)
            if (d.difficultyNames[i] != null && d.difficultyNames[i].equalsIgnoreCase(difficulty))
                return Math.max(0.1f, d.difficultyLevelCost[i]);
        return 1f;
    }

    private static float costFactor() {
        AdventurePlayer player = WorldSave.getCurrentSave() == null ? null : WorldSave.getCurrentSave().getPlayer();
        return player == null || player.getDifficulty() == null ? 1f : costFactor(player.getDifficulty().name);
    }

    public static int levelFor(int power) {
        return levelFor(power, costFactor());
    }

    static int levelFor(int power, float factor) {
        int level = 0;
        int spent = 0;
        while (level < 999) {
            int need = powerToNext(level, factor);
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
        return powerAtLevel(level, costFactor());
    }

    private static int powerAtLevel(int level, float factor) {
        int total = 0;
        for (int l = 0; l < level; l++)
            total += powerToNext(l, factor);
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

    /** The refusal for an item that equip() would not put on - for the screens' message. Round 502: equip() keeps the
     *  reason it gave (the main-item limit or the companion limit), so the message names the right one. */
    public static String refusalFor(AdventurePlayer player, ItemData item) {
        if (player.lastEquipRefusal() != null)
            return player.lastEquipRefusal();
        String why = item == null ? null : equipRefusal(player, item, item.equipmentSlot);
        return why != null ? why : "Your power allows no more main items yet.";
    }

    /** "Main items 1 / 2" for the inventory and Armory screens; "" when the character has no Ascendance. */
    public static String mainItemsLabel(AdventurePlayer player) {
        if (!isActive())
            return "";
        return "Main items " + mainItemsWorn(player) + " / " + mainSlotAllowance();
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

    // ------------------------------------------------------------------------------------------------ companions (round 502)
    // The item audit (docs/audits/2026-10-08-items-mana-and-units.md) found 127 items that start a creature on the
    // battlefield, in every slot - the user: "I think we need to possible re-allocate some of them to prevent someone from
    // starting a duel with 3 or 4 creatures", then "Go with the companion limit, 2 at Ascendance 15 and 3 at 25". A
    // companion is such an item, worn in ANY slot (the gauntlets' Left2/Right2 and the Token slot too): a rule on the
    // item, not the slot, so every item keeps its slot and its theme. Unlike the main-item limit it holds without
    // Ascendance as well (companionsWithoutAscendance) - switching leveling off must not lift it.

    /** The slots kept longest when companions must come off: Boots, the companion slot of old, first. */
    private static final List<String> COMPANION_KEEP_ORDER = java.util.Arrays.asList(
            "Boots", "Body", "Neck", "Left", "Right", "Token", "Left2", "Right2");

    private static final java.util.Map<String, Integer> CREATURES = new java.util.HashMap<>();

    /** Creatures this item starts on the battlefield - its startBattleWithCard and ...Tapped cards that are creatures (the
     *  command zone is not the battlefield; an "enters the battlefield" token maker makes nothing at the start, Player.java
     *  puts start cards in play without a trigger). Cached by the item's name and cards. */
    public static int startingCreatures(ItemData item) {
        if (item == null || item.effect == null)
            return 0;
        EffectData e = item.effect;
        if (e.startBattleWithCard == null && e.startBattleWithCardTapped == null)
            return 0;
        String key = item.name + "|" + java.util.Arrays.toString(e.startBattleWithCard) + "|"
                + java.util.Arrays.toString(e.startBattleWithCardTapped);
        Integer known = CREATURES.get(key);
        if (known != null)
            return known;
        int creatures = 0;
        for (IPaperCard card : e.startBattleWithCards())
            if (isCreature(card))
                creatures++;
        for (IPaperCard card : e.startBattleWithCardsTapped())
            if (isCreature(card))
                creatures++;
        CREATURES.put(key, creatures);
        return creatures;
    }

    private static boolean isCreature(IPaperCard card) {
        return card != null && card.getRules() != null && card.getRules().getType().isCreature();
    }

    public static boolean isCompanion(ItemData item) {
        return startingCreatures(item) > 0;
    }

    /** Companions the player may wear now: by level with Ascendance, companionsWithoutAscendance without it. */
    public static int companionAllowance() {
        return isActive() ? companionAllowance(level()) : Math.max(1, data().companionsWithoutAscendance);
    }

    public static int companionAllowance(int level) {
        AscendanceData d = data();
        int allowed = d.companionBase;
        for (int l : d.companionLevels)
            if (level >= l)
                allowed++;
        return Math.max(1, allowed);
    }

    /** The next level that allows one more companion, or -1 when none is left (or the character has no Ascendance). */
    public static int nextCompanionLevel() {
        if (!isActive())
            return -1;
        int level = level();
        for (int l : data().companionLevels)
            if (l > level)
                return l;
        return -1;
    }

    /** Worn companions' names, leaving out what is in {@code exceptSlot} (what an equip there would take off; null for
     *  none). */
    private static List<String> companionsWornNames(AdventurePlayer player, String exceptSlot) {
        List<String> names = new ArrayList<>();
        for (String slot : player.equippedSlots()) {
            if (slot.equals(exceptSlot))
                continue;
            ItemData worn = player.getEquippedItem(player.itemInSlot(slot));
            if (isCompanion(worn))
                names.add(worn.name);
        }
        return names;
    }

    public static int companionsWorn(AdventurePlayer player) {
        return companionsWornNames(player, null).size();
    }

    /** Why this item may not go into {@code slot} now (what is worn there comes off, so a companion for a companion is
     *  always a fair swap), or null when it may. */
    public static String companionRefusal(AdventurePlayer player, ItemData item, String slot) {
        if (!isCompanion(item))
            return null;
        int allowed = companionAllowance();
        List<String> worn = companionsWornNames(player, slot);
        if (worn.size() < allowed)
            return null;
        int next = nextCompanionLevel();
        String rule = allowed == 1 ? "1 companion (an item that starts a creature in play)"
                : allowed + " companions (items that start a creature in play)";
        return (isActive() ? "Your power allows " + rule + (next > 0 ? " - Ascendance " + next + " brings one more." : ".")
                : "You may wear " + rule + ".") + " Worn: " + String.join(", ", worn) + ".";
    }

    /** "Companions 0 / 1" for the Armory screen and the Ascendance status. */
    public static String companionsLabel(AdventurePlayer player) {
        return "Companions " + companionsWorn(player) + " / " + companionAllowance();
    }

    /** The limits an item counts against, as one line for the inventory and Armory descriptions: "Main items 1 / 2 - one
     *  more at Ascendance 10", "Companions 0 / 1 - one more at Ascendance 15", or both counts for a main-slot companion.
     *  ItemData.getDescription marks the companion itself ("Slot: Right - Companion"), in shops too. */
    public static String itemLimitLine(AdventurePlayer player, ItemData item) {
        boolean main = isActive() && item != null && isMainSlot(item.equipmentSlot);
        boolean companion = isCompanion(item);
        String text;
        if (main && companion) {
            text = mainItemsLabel(player) + ", c" + companionsLabel(player).substring(1);
        } else if (main) {
            int next = nextMainSlotLevel();
            text = mainItemsLabel(player) + (next > 0 ? " - one more at Ascendance " + next : "");
        } else if (companion) {
            int next = nextCompanionLevel();
            text = companionsLabel(player) + (next > 0 ? " - one more at Ascendance " + next : "");
        } else {
            return "";
        }
        return "[%85]" + text;
    }

    /** The description with the limit line right under it - no blank line between (the description ends in a line
     *  break, and the box shows only a few lines before it scrolls). */
    public static String withItemLimits(AdventurePlayer player, ItemData item, String description) {
        String line = itemLimitLine(player, item);
        if (line.isEmpty())
            return description;
        int end = description.length();
        while (end > 0 && description.charAt(end - 1) == '\n')
            end--;
        return description.substring(0, end) + "\n" + line;
    }

    /** Take companions off until the count fits: a save loaded, a new run, Ascendance switched on, a deck's loadout. The
     *  gauntlets' second hands and the Token slot go first, Boots last. */
    public static void enforceCompanionLimit(AdventurePlayer player, String why) {
        int allowed = companionAllowance();
        if (companionsWorn(player) <= allowed)
            return;
        List<String> slots = new ArrayList<>();
        for (String slot : player.equippedSlots())
            if (isCompanion(player.getEquippedItem(player.itemInSlot(slot))))
                slots.add(slot);
        slots.sort(java.util.Comparator.comparingInt(slot -> {
            int keep = COMPANION_KEEP_ORDER.indexOf(slot);
            return keep < 0 ? COMPANION_KEEP_ORDER.size() : keep;
        }));
        List<String> removed = new ArrayList<>();
        for (int i = slots.size() - 1; i >= 0 && companionsWorn(player) > allowed; i--) {
            if (player.itemInSlot(slots.get(i)) == null) // a gauntlet taken off took its second hand with it
                continue;
            String name = player.takeOffSlot(slots.get(i));
            if (name != null)
                removed.add(name);
        }
        if (removed.isEmpty())
            return;
        System.out.println("[TFR-Companion] " + why + ": " + removed.size() + " companion(s) taken off to fit " + allowed
                + (isActive() ? " at Ascendance " + level() : " (no Ascendance)") + " - " + removed);
        GameHUD.getInstance().addNotification("You may wear " + allowed + " companion" + (allowed == 1 ? "" : "s")
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

    /** A new character (New Game) or a New Game+: back to Ascendance 0 (round 497), on when the plane has it on. */
    public static void startRun(AdventurePlayer player, String why) {
        AscendanceState s = player.ascendance();
        s.reset();
        s.on = enabledForNewRuns();
        clearPendingDuelPower(); // round 517
        player.requestCompanionCheck(); // round 502: the kept gear is checked once the run is on screen
        System.out.println("[TFR-Ascend] " + why + ": Ascendance " + (s.on ? "on - level 0, " + mainSlotAllowance(0)
                + " main item(s), " + companionAllowance(0) + " companion(s)" : "off (config.json ascendanceEnabled)"));
    }

    /** Award Power for {@code source} (times the config's levelingSpeed); level-ups pay their rewards at once. */
    public static void award(int base, String source) {
        if (base <= 0)
            return;
        awardExact(scaled(base), source);
    }

    /** {@code base} times the config's levelingSpeed - what an award pays. */
    private static int scaled(int base) {
        return Math.max(1, Math.round(base * Math.max(0f, data().levelingSpeed)));
    }

    /** Round 517: the icon every Power figure in text carries - items.atlas "PowerGlyph", Shikashi's radiant sun cut to
     *  16 px (the 32-px "Power" region, which the loot card and the HUD panel draw, overruns a line of text). */
    public static final String ICON = "[+PowerGlyph]";

    private static void awardExact(int amount, String source) {
        AscendanceState s = state();
        if (s == null || !isActive() || amount <= 0)
            return;
        int before = levelFor(s.power);
        s.power += amount;
        int after = levelFor(s.power);
        System.out.println("[TFR-Ascend] +" + amount + " Power - " + source + " (total " + s.power + ", level " + after + ")");
        // Round 517: authored on the paper, so the sun keeps its colors (the black tint drew it solid black).
        GameHUD.getInstance().addNotification(onPaper("[BLACK][%85]+" + amount + " " + ICON + " - " + source), true);
        for (int level = before + 1; level <= after; level++)
            onLevelUp(level);
    }

    // Round 517 (the user: "when you win a duel, have it as a reward, and only apply the Power once you open/collect your
    // reward"): a won duel's Power waits for its loot screen - MapStage.getReward / WorldStage add it as a Power card
    // (appendDuelPower), and collecting the card pays it (AdventurePlayer.addReward -> collectPower). Left on the
    // screen, it is lost like the rest of the loot.
    private static int pendingDuelPower;
    private static String pendingDuelSource = "";
    /** The enemy whose win it is - a second report of the same duel (the agent bridge's win screen) queues nothing. */
    private static EnemySprite pendingDuelEnemy;

    /** The loot of the duel just won gains its Power card (nothing when the duel paid none). */
    public static void appendDuelPower(com.badlogic.gdx.utils.Array<Reward> loot) {
        if (pendingDuelPower <= 0 || loot == null)
            return;
        loot.add(Reward.power(pendingDuelPower, pendingDuelSource));
        System.out.println("[TFR-Ascend] " + pendingDuelPower + " Power on the loot screen - " + pendingDuelSource);
        pendingDuelPower = 0;
        pendingDuelSource = "";
        pendingDuelEnemy = null;
    }

    /** A Power card collected from a loot screen - already scaled by the leveling speed. */
    public static void collectPower(int amount, String source) {
        awardExact(amount, source == null || source.isEmpty() ? "loot" : source);
    }

    /** A duel's Power that never reached a loot screen (a fight with no loot path) is paid rather than lost. */
    private static void payUncollectedDuelPower(String why) {
        if (pendingDuelPower <= 0)
            return;
        int amount = pendingDuelPower;
        String source = pendingDuelSource;
        pendingDuelPower = 0;
        pendingDuelSource = "";
        pendingDuelEnemy = null;
        System.out.println("[TFR-Ascend] " + amount + " Power for " + source + " never reached a loot screen (" + why
                + ") - paid now");
        awardExact(amount, source);
    }

    /** A save loaded or a run started: nothing is waiting from another game. */
    private static void clearPendingDuelPower() {
        pendingDuelPower = 0;
        pendingDuelSource = "";
        pendingDuelEnemy = null;
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
            gifts.add("+" + life + " [+Life] max life");
        }
        if (contains(d.mainSlotLevels, level))
            gifts.add(mainSlotAllowance(level) + " main items");
        if (contains(d.companionLevels, level)) // round 502
            gifts.add(companionAllowance(level) + " companions");
        String title = contains(d.titleLevels, level) ? titleAt(level) : "";
        boolean choice = gifts.isEmpty() && title.isEmpty() && !pastCurve;
        if (choice) {
            s.pendingLevels.add(level);
            gifts.add("a reward to choose - tap the Asc panel");
        } else { // round 496: the level sheet's line for a fixed level
            record(level, String.join(", ", gifts) + (title.isEmpty() ? "" : " - " + title));
        }
        System.out.println("[TFR-Ascend] LEVEL " + level + (title.isEmpty() ? "" : " - " + title) + ": "
                + String.join(", ", gifts) + " (choices waiting: " + s.pendingLevels.size() + ")");
        // Round 496 (the user: "The text is white, should be black"): an authored banner on the paper notification opens
        // black; the level in a dark gold that reads on paper; each icon drawn [WHITE] so the black tint does not darken it.
        GameHUD.getInstance().addNotification(onPaper("[#8A5A00]Ascendance " + level + (title.isEmpty() ? "" : " - " + title)
                + "![BLACK] " + capitalize(String.join(", ", gifts)) + "."), true);
    }

    /** Round 496: black text for the paper banner, every [+Icon] in its own colors. */
    private static String onPaper(String text) {
        String body = text.replaceAll("(\\[\\+[A-Za-z0-9_]+\\])", "[WHITE]$1[BLACK]");
        return body.startsWith("[") ? body : "[BLACK]" + body;
    }

    /** Round 496: what a level gave, for the level sheet (the user: "We should add a Level sheet that shows what you picked
     *  on each level"). */
    private static void record(int level, String what) {
        AscendanceState s = state();
        if (s == null)
            return;
        s.history.removeIf(entry -> entry.startsWith(level + "|"));
        s.history.add(level + "|" + what);
    }

    /** Round 496: level -> what it gave, oldest first; waiting levels and levels from before the sheet existed have none. */
    public static java.util.TreeMap<Integer, String> levelHistory() {
        java.util.TreeMap<Integer, String> out = new java.util.TreeMap<>();
        AscendanceState s = state();
        if (s == null)
            return out;
        for (String entry : s.history) {
            int bar = entry.indexOf('|');
            if (bar > 0)
                try {
                    out.put(Integer.parseInt(entry.substring(0, bar)), entry.substring(bar + 1));
                } catch (NumberFormatException ignored) {
                }
        }
        return out;
    }

    public static List<Integer> pendingLevelList() {
        AscendanceState s = state();
        return s == null ? new ArrayList<>() : new ArrayList<>(s.pendingLevels);
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
        String source = "beat " + e.getName() + " (" + EnemyData.tierDisplayName(e.tier) + (why.isEmpty() ? "" : ", " + why)
                + (outgrown < 1f ? ", outgrown " + Math.round(outgrown * 100) + "%" : "") + ")";
        if (arena) { // an Arena match has no loot screen of its own - paid at once, as before
            award(amount, source);
            return;
        }
        if (pendingDuelPower > 0 && enemy == pendingDuelEnemy) { // round 517: the same duel reported twice
            System.out.println("[TFR-Ascend] " + e.getName() + "'s win was reported again - its Power already waits");
            return;
        }
        payUncollectedDuelPower("another duel was won first"); // round 517
        pendingDuelPower = scaled(amount);
        pendingDuelSource = source;
        pendingDuelEnemy = enemy;
    }

    /** AdventureQuestController.showQuestDialogs: a quest done. An invasion pays by the toughest troop it lost. */
    public static void onQuestCompleted(AdventureQuestData quest) {
        onQuestCompleted(quest, false);
    }

    /** {@code afterDialogs}: the quest completed in the same pass its prologue was first shown, so its Power waits until
     *  the queued dialogs are read (payDeferred). Round 505 - the intro, quest 28, has no stages: it completes the moment
     *  the start map loads and its prologue IS the tutorial-or-skip choice; the user: "I just started a new game and was
     *  immediately level 1. That does not seem correct. I did not even choose yet to do or skip the tutorial." */
    public static void onQuestCompleted(AdventureQuestData quest, boolean afterDialogs) {
        if (!isActive() || quest == null)
            return;
        AscendanceData d = data();
        int amount;
        String source;
        if (InvasionQuests.isInvasion(quest)) {
            int rank = Math.max(0, Math.min(d.invasion.length - 1, InvasionQuests.toughestTroopRank(quest)));
            amount = d.invasion[rank];
            source = "invasion repelled";
        } else if (contains(d.noPowerQuestIds, quest.getID())) {
            // Round 496 (the user: "I started two games, one I chose the tutorial and the other I skipped. Both started me
            // off at level 2"): the intro quest completes the moment either start ends - it pays nothing.
            System.out.println("[TFR-Ascend] no Power for " + quest.name + " (noPowerQuestIds)");
            return;
        } else if (quest.storyQuest) {
            amount = d.storyQuest;
            source = "story: " + quest.name;
        } else {
            amount = d.sideQuest;
            source = "quest: " + quest.name;
        }
        AscendanceState s = state();
        if (!afterDialogs || s == null) {
            award(amount, source);
            return;
        }
        s.deferredPower += amount;
        s.deferredSource = source;
        System.out.println("[TFR-Ascend] " + amount + " Power for " + source + " waits until its dialog is read");
    }

    /** Round 505: pay the Power a quest's dialog was holding - AdventureQuestController once its last queued dialog
     *  closes, the HUD after a load (a save made while the dialog was open). */
    public static void payDeferred() {
        AscendanceState s = state();
        if (s == null)
            return;
        s.deferredLoaded = false;
        if (s.deferredPower <= 0)
            return;
        int amount = s.deferredPower;
        String source = s.deferredSource;
        s.deferredPower = 0;
        s.deferredSource = "";
        // Round 505b: the dialog started the tutorial - the tutorial quest pays when it is done, the intro nothing.
        AdventurePlayer player = WorldSave.getCurrentSave().getPlayer();
        for (AdventureQuestData quest : player.getQuests())
            if (quest != null && contains(data().tutorialQuestIds, quest.getID())) {
                System.out.println("[TFR-Ascend] " + amount + " Power for " + source + " dropped - the tutorial ("
                        + quest.name + ") pays when it is done");
                return;
            }
        award(amount, source);
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

    // ------------------------------------------------------------------------------------------------ the choices (round 494)
    // The user: "I'm thinking with each level you get something like a choice of 3 from the following list: +3 life for
    // the first duel each day (the second time you take this it would be the first 2 duels, etc.), + xxx Gold, + xxx
    // Shards, + xxx Wood, + xxx Stone, + random rare card(s), + random common item, + ????" - and on the stacking: "Let's
    // say you choose Haggler. -5% shop prices, then the next time it would be -10% and the 3rd -15%. So it's like a mini
    // decision, if you want to invest heavily into one direction." Pool and numbers: ascendance.json "choices".

    private static final java.util.Random RAND = new java.util.Random();

    private static AscendanceData.Choice choice(String id) {
        for (AscendanceData.Choice c : data().choices)
            if (c != null && c.id.equals(id))
                return c;
        return null;
    }

    /** Picks of a lasting reward taken so far - 0 when Ascendance is off (every effect below reads neutral then). */
    public static int picks(String id) {
        AscendanceState s = state();
        if (s == null || !isActive())
            return 0;
        Integer n = s.picks.get(id);
        return n == null ? 0 : n;
    }

    /** A lasting reward's total: its per-pick value times the picks taken. */
    private static float total(String id) {
        return totalAt(choice(id), picks(id));
    }

    /** Round 508: the total after {@code picks} picks - {@code first} for the first when the choice sets one (Shardwell
     *  +2, then +3), {@code value} for each pick after it. */
    private static float totalAt(AscendanceData.Choice c, int picks) {
        if (c == null || picks <= 0)
            return 0f;
        return c.first > 0f ? c.first + c.value * (picks - 1) : c.value * picks;
    }

    // The effects, read where the game computes each value. Neutral (1, or 0) without the pick.
    /** Haggler: the price the player pays in a shop, times this. */
    public static float shopPriceFactor() { return Math.max(0.1f, 1f - total("haggler")); }
    /** Swift Feet: overworld speed, times this. */
    public static float speedFactor() { return 1f + total("swiftFeet"); }
    /** Prospector: resource pickups and mine yields, times this. */
    public static float prospectorFactor() { return 1f + total("prospector"); }
    /** Far Sight: vision radius, times this. */
    public static float visionFactor() { return 1f + total("farSight"); }
    /** Marshal: added to a roaming guard's duel life. */
    public static int guardLifeBonus() { return Math.round(total("marshal")); }
    /** Stubborn: the life and gold a defeat costs, times this. */
    public static float defeatLossFactor() { return Math.max(0f, 1f - total("stubborn")); }
    /** Mender: the chance an equipped item escapes cracking on a defeat. */
    public static float crackSaveChance() { return Math.min(1f, total("mender")); }
    /** Mender: does this item escape the crack a defeat would give it? Rolled once per defeat. */
    public static boolean itemEscapesCracking(ItemData item) {
        float chance = crackSaveChance();
        if (chance <= 0f || RAND.nextFloat() >= chance)
            return false;
        System.out.println("[TFR-Ascend] Mender: " + item.name + " escapes cracking (" + pct(chance) + " chance)");
        GameHUD.getInstance().addNotification("Mender: your " + item.name + " held together.");
        return true;
    }

    /** Spoilsman: cards added to a first win's reward. */
    public static int firstWinCardBonus() { return Math.round(total("spoilsman")); }
    /** Shardwell: mana shards added at the start of the player's duels. */
    public static int duelStartShards() { return Math.round(total("shardwell")); }
    /** Envoy: color reputation LOST from a won duel, times this. */
    public static float reputationLossFactor() { return Math.max(0f, 1f - total("envoy")); }
    /** Round 508, Mechanic (the one-time Mend became it - the user: "Change 'Mend' to Mechanic and reduce item repair cost
     *  by 75%"): an item repair's gold, times this. */
    public static float repairCostFactor() { return Math.max(0f, 1f - total("mechanic")); }
    /** Round 508, Medic (the user: "Guards heal time reduced by 50%"): a defeated roaming guard's days out, times this. */
    public static float guardRecoveryFactor() { return Math.max(0.1f, 1f - total("medic")); }
    /** Architect: building and town-restore costs, times this. */
    public static float buildCostFactor() { return Math.max(0.1f, 1f - total("architect")); }

    /** Morning Vigor: the life added to this duel - "+3 life for the first duel each day", one more duel each pick.
     *  Counts the duel (call once, at a duel's start). */
    public static int morningVigorLife() {
        AscendanceState s = state();
        int picks = picks("vigor");
        if (s == null || picks <= 0)
            return 0;
        int day = WorldSave.getCurrentSave().getWorld().getCurrentDay();
        if (s.vigorDay != day) {
            s.vigorDay = day;
            s.vigorUsed = 0;
        }
        if (s.vigorUsed >= picks)
            return 0;
        s.vigorUsed++;
        int life = Math.round(choice("vigor").value);
        System.out.println("[TFR-Ascend] Morning Vigor: +" + life + " life this duel (" + s.vigorUsed + " of " + picks
                + " today, day " + day + ")");
        return life;
    }

    /** The offer for the oldest waiting level - rolled once and kept (closing the dialog does not re-roll it). Empty
     *  when nothing waits. */
    public static List<String> currentOffer() {
        AscendanceState s = state();
        if (s == null || !isActive() || s.pendingLevels.isEmpty())
            return new ArrayList<>();
        boolean stale = s.offer.isEmpty();
        for (String id : s.offer)
            stale |= !offerable(choice(id), s.pendingLevels.get(0));
        if (stale)
            rollOffer(s);
        return new ArrayList<>(s.offer);
    }

    public static int offerLevel() {
        AscendanceState s = state();
        return s == null || s.pendingLevels.isEmpty() ? 0 : s.pendingLevels.get(0);
    }

    private static boolean offerable(AscendanceData.Choice c, int level) {
        if (c == null)
            return false;
        if (c.lasting)
            return picks(c.id) < c.maxPicks;
        return AscendanceRewards.canGive(c.id, level);
    }

    /** At least one lasting and one one-time reward while any are left (the user: "a choice of 3"), weighted. */
    private static void rollOffer(AscendanceState s) {
        rollOffer(s, java.util.Collections.emptySet());
    }

    /** Round 518: {@code avoid} (the offer a Re-roll replaces) is drawn only when too few others are left to fill it. */
    private static void rollOffer(AscendanceState s, java.util.Set<String> avoid) {
        int level = s.pendingLevels.get(0);
        List<AscendanceData.Choice> lasting = new ArrayList<>(), once = new ArrayList<>();
        List<AscendanceData.Choice> freshLasting = new ArrayList<>(), freshOnce = new ArrayList<>();
        for (AscendanceData.Choice c : data().choices)
            if (offerable(c, level)) {
                (c.lasting ? lasting : once).add(c);
                if (!avoid.contains(c.id))
                    (c.lasting ? freshLasting : freshOnce).add(c);
            }
        s.offer.clear();
        addDraw(s, freshLasting.isEmpty() ? lasting : freshLasting);
        addDraw(s, freshOnce.isEmpty() ? once : freshOnce);
        List<AscendanceData.Choice> rest = new ArrayList<>(freshLasting);
        rest.addAll(freshOnce);
        int size = Math.max(1, data().offerSize);
        while (s.offer.size() < size && addDraw(s, rest)) {
            // draw until the offer is full or the fresh ones run out
        }
        List<AscendanceData.Choice> any = new ArrayList<>(lasting); // the avoided ones, only to fill the offer
        any.addAll(once);
        while (s.offer.size() < size && addDraw(s, any)) {
            // likewise
        }
        java.util.Collections.shuffle(s.offer, RAND);
        System.out.println("[TFR-Ascend] offer for level " + level + ": " + s.offer
                + (avoid.isEmpty() ? "" : " (re-rolled from " + avoid + ")"));
    }

    /** Round 518: one draw from {@code from} onto the offer; false when nothing is left to draw. */
    private static boolean addDraw(AscendanceState s, List<AscendanceData.Choice> from) {
        AscendanceData.Choice picked = draw(from);
        if (picked == null)
            return false;
        s.offer.add(picked.id);
        return true;
    }

    /** Round 518: the Re-roll's price in shards on this character's difficulty (difficultyRerollCost). */
    public static int rerollCost() {
        AscendanceData d = data();
        AdventurePlayer player = WorldSave.getCurrentSave() == null ? null : WorldSave.getCurrentSave().getPlayer();
        String difficulty = player == null || player.getDifficulty() == null ? "Normal" : player.getDifficulty().name;
        for (int i = 0; i < Math.min(d.difficultyNames.length, d.difficultyRerollCost.length); i++)
            if (d.difficultyNames[i] != null && d.difficultyNames[i].equalsIgnoreCase(difficulty))
                return Math.max(0, d.difficultyRerollCost[i]);
        return 40; // a difficulty the table does not list: Normal's
    }

    /** Round 518: whether the waiting level's offer can still be re-rolled (once per level). */
    public static boolean canReroll() {
        AscendanceState s = state();
        return s != null && isActive() && !s.pendingLevels.isEmpty() && s.rerolledLevel != s.pendingLevels.get(0);
    }

    /** Round 518: the choice dialog's Re-roll - pays rerollCost() shards and draws a new offer for the waiting level,
     *  away from the one it replaces. Once per level. Returns false (and changes nothing) when it cannot. */
    public static boolean reroll() {
        AscendanceState s = state();
        if (!canReroll())
            return false;
        AdventurePlayer player = WorldSave.getCurrentSave().getPlayer();
        int cost = rerollCost();
        if (player.getShards() < cost) {
            System.out.println("[TFR-Ascend] re-roll refused: " + player.getShards() + " shards, it costs " + cost);
            return false;
        }
        java.util.Set<String> old = new java.util.HashSet<>(currentOffer());
        player.takeShards(cost);
        s.rerolledLevel = s.pendingLevels.get(0);
        System.out.println("[TFR-Ascend] level " + s.rerolledLevel + " re-rolled for " + cost + " shards");
        rollOffer(s, old);
        return true;
    }

    /** One weighted draw, removed from the list - and the same id never twice in an offer. */
    private static AscendanceData.Choice draw(List<AscendanceData.Choice> from) {
        AscendanceState s = state();
        from.removeIf(c -> s != null && s.offer.contains(c.id));
        if (from.isEmpty())
            return null;
        float sum = 0f;
        for (AscendanceData.Choice c : from)
            sum += Math.max(0f, c.weight);
        float roll = RAND.nextFloat() * sum;
        AscendanceData.Choice picked = from.get(from.size() - 1);
        for (AscendanceData.Choice c : from) {
            roll -= Math.max(0f, c.weight);
            if (roll <= 0f) {
                picked = c;
                break;
            }
        }
        from.remove(picked);
        return picked;
    }

    private static final String[] RANKS = {"", " I", " II", " III", " IV", " V"};

    /** "[%95]Haggler II[]\n[%75]Shop prices -10% (now -5%)" - the dialog's button text for one offered reward. */
    public static String describe(String id, int level) {
        AscendanceData.Choice c = choice(id);
        if (c == null)
            return id;
        if (!c.lasting)
            return "[%95]" + AscendanceRewards.name(id) + "[]\n[%75]" + AscendanceRewards.describe(id, c.value, level);
        int next = picks(id) + 1;
        String rank = next < RANKS.length ? RANKS[next] : " " + next;
        float now = totalAt(c, picks(id)), then = totalAt(c, next); // round 508: a different first pick (Shardwell)
        String effect;
        switch (id) {
            case "vigor": effect = "+" + Math.round(c.value) + " [+Life] in the first " + next + " duel" + (next == 1 ? "" : "s") + " each day"; break;
            case "haggler": effect = "Shop prices -" + pct(then) + " [+Gold]" + (now > 0 ? " (now -" + pct(now) + ")" : ""); break;
            case "swiftFeet": effect = "Overworld speed +" + pct(then) + (now > 0 ? " (now +" + pct(now) + ")" : ""); break;
            case "prospector": effect = "Resource pickups and mines +" + pct(then) + (now > 0 ? " (now +" + pct(now) + ")" : ""); break;
            case "farSight": effect = "Vision +" + pct(then) + (now > 0 ? " (now +" + pct(now) + ")" : ""); break;
            case "marshal": effect = "Roaming guards +" + Math.round(then) + " [+Life]" + (now > 0 ? " (now +" + Math.round(now) + ")" : ""); break;
            case "stubborn": effect = "Defeats cost " + pct(then) + " less [+Life] and [+Gold]" + (now > 0 ? " (now " + pct(now) + ")" : ""); break;
            case "mender": effect = pct(Math.min(1f, then)) + " chance a worn item escapes cracking" + (now > 0 ? " (now " + pct(now) + ")" : ""); break;
            case "spoilsman": effect = "+" + Math.round(then) + " card" + (then >= 2 ? "s" : "") + " on a first win against an enemy"; break;
            case "shardwell": effect = "+" + Math.round(then) + " [+Shards] at the start of each duel" + (now > 0 ? " (now +" + Math.round(now) + ")" : ""); break;
            case "envoy": effect = "Color reputation lost from wins -" + pct(then) + (now > 0 ? " (now -" + pct(now) + ")" : ""); break;
            case "architect": effect = "Building and town restore costs -" + pct(then) + (now > 0 ? " (now -" + pct(now) + ")" : ""); break;
            case "mechanic": effect = "Item repairs cost " + pct(Math.min(1f, then)) + " less [+Gold]"; break; // round 508
            case "medic": effect = "Downed roaming guards heal in " + pct(Math.min(1f, then)) + " less time"; break; // round 508
            default: effect = id;
        }
        return "[%95]" + lastingName(id) + rank + "[]\n[%75]" + effect;
    }

    private static String pct(float share) {
        return Math.round(share * 100f) + "%";
    }

    public static String lastingName(String id) {
        switch (id) {
            case "vigor": return "Morning Vigor";
            case "haggler": return "Haggler";
            case "swiftFeet": return "Swift Feet";
            case "prospector": return "Prospector";
            case "farSight": return "Far Sight";
            case "marshal": return "Marshal";
            case "stubborn": return "Stubborn";
            case "mender": return "Mender";
            case "spoilsman": return "Spoilsman";
            case "shardwell": return "Shardwell";
            case "envoy": return "Envoy";
            case "architect": return "Architect";
            case "mechanic": return "Mechanic"; // round 508
            case "medic": return "Medic";
            default: return id;
        }
    }

    /** Round 508: Medic just taken - every roaming guard still out of commission has its remaining days cut by the same
     *  share (rounded up, at least a day), so the pick helps the guards already down, not only the next defeat. */
    private static void shortenDowntimes() {
        int day = WorldSave.getCurrentSave().getWorld().getCurrentDay();
        float factor = guardRecoveryFactor();
        for (RoamingGuardData guard : RoamingGuards.roster())
            if (guard.isOutOfCommission(day)) {
                int left = guard.downUntilDay - day;
                int before = guard.downUntilDay;
                guard.downUntilDay = day + Math.max(1, (int) Math.ceil(left * factor));
                System.out.println("[TFR-Ascend] Medic: a " + RoamingGuards.displayName(guard.tier) + " guard out until day "
                        + before + " is back on day " + guard.downUntilDay);
            }
    }

    /** Take {@code id} from the current offer: a lasting pick counts, a one-time reward is paid at the level it was
     *  earned. Returns what happened, for the dialog's toast; null when {@code id} is not on offer. */
    public static String choose(String id) {
        AscendanceState s = state();
        if (s == null || !isActive() || s.pendingLevels.isEmpty() || !currentOffer().contains(id))
            return null;
        AscendanceData.Choice c = choice(id);
        int level = s.pendingLevels.remove(0);
        s.offer.clear();
        String result;
        if (c.lasting) {
            s.picks.merge(id, 1, Integer::sum);
            result = lastingName(id) + RANKS[Math.min(RANKS.length - 1, s.picks.get(id))];
            if ("swiftFeet".equals(id)) // the player sprite caches its speed until the equipment signal
                WorldSave.getCurrentSave().getPlayer().refreshEquipmentEffects();
            if ("medic".equals(id)) // round 508: a guard already down heals in the shorter time too
                shortenDowntimes();
        } else {
            result = AscendanceRewards.give(id, c.value, level);
        }
        System.out.println("[TFR-Ascend] level " + level + " reward chosen: " + id + " -> " + result + " (still waiting: "
                + s.pendingLevels.size() + ")");
        record(level, result); // round 496: the level sheet
        GameHUD.getInstance().addNotification(result); // round 496: black on the paper; icons keep their colors
        return result;
    }

    // ------------------------------------------------------------------------------------------------ save

    /** AdventurePlayer.save: the state under its own keys (no serialized object - see AscendanceState). */
    public static void save(forge.adventure.util.SaveFileData data, AscendanceState s) {
        data.store("ascendanceOn", s.on);
        data.store("ascendancePower", s.power);
        // Round 497: the curve starts at level 0 (2) and costs by difficulty (3). Round 516 (code review): the version the
        // Power is really priced on - a load whose config failed keeps its old mark, so the migration is not skipped for good.
        data.store("ascendanceCurve", s.curve);
        data.storeObject("ascendancePendingLevels", new ArrayList<>(s.pendingLevels)); // round 494
        data.storeObject("ascendanceOffer", new ArrayList<>(s.offer));
        data.storeObject("ascendancePickIds", new ArrayList<>(s.picks.keySet()));
        data.storeObject("ascendancePickCounts", new ArrayList<>(s.picks.values()));
        data.storeObject("ascendanceHistory", new ArrayList<>(s.history)); // round 496: the level sheet
        data.store("ascendanceVigorDay", s.vigorDay);
        data.store("ascendanceVigorUsed", s.vigorUsed);
        data.store("ascendanceDeferredPower", s.deferredPower); // round 505
        data.store("ascendanceDeferredSource", s.deferredSource == null ? "" : s.deferredSource);
        data.store("ascendanceRerolledLevel", s.rerolledLevel); // round 518
    }

    /** AdventurePlayer.load: absent keys (every save before round 493) read as "off". */
    public static void load(forge.adventure.util.SaveFileData data, AscendanceState s, String difficulty) {
        s.reset();
        clearPendingDuelPower(); // round 517: a loot screen of the game before is gone
        s.on = data.containsKey("ascendanceOn") && data.readBool("ascendanceOn");
        s.power = data.containsKey("ascendancePower") ? Math.max(0, data.readInt("ascendancePower")) : 0;
        int curve = data.containsKey("ascendanceCurve") ? data.readInt("ascendanceCurve") : 1;
        // Round 516 (code review): without the config's curve nothing can be re-priced - keep the saved mark, so the
        // migration runs on a later load that has it.
        boolean curveLoaded = data().xpToNext.length > 0;
        s.curve = curveLoaded ? 3 : curve;
        if (!curveLoaded && s.on && curve < 3)
            System.out.println("[TFR-Ascend] ascendance.json has no curve - this save's Power stays on curve " + curve
                    + " until a load that can re-price it");
        if (s.on && curve < 2 && curveLoaded) {
            // Round 497: a character saved before level 0 existed keeps the level it had - its Power gains the new
            // first step, so its waiting choices and level sheet still match.
            s.power += data().xpToNext[0];
            System.out.println("[TFR-Ascend] a save from before level 0: +" + data().xpToNext[0] + " Power so it keeps level "
                    + levelFor(s.power, 1f));
        }
        float factor = costFactor(difficulty);
        if (s.on && curve < 3 && factor != 1f && curveLoaded) {
            // Round 497: and before the difficulty's level cost - the same level and the same share of the way to the
            // next, re-priced, so nothing it was given is earned twice.
            int level = levelFor(s.power, 1f);
            int into = s.power - powerAtLevel(level, 1f);
            int before = s.power;
            s.power = powerAtLevel(level, factor)
                    + Math.round(into * (powerToNext(level, factor) / (float) powerToNext(level, 1f)));
            System.out.println("[TFR-Ascend] a save from before the difficulty's level cost (" + difficulty + " x" + factor
                    + "): Power " + before + " -> " + s.power + ", still level " + levelFor(s.power, factor));
        }
        loadChoices(data, s); // round 494
    }

    /** Round 494: the choice state. A round-493 save kept only a count of waiting choices: rebuild their levels from the
     *  level reached (the last non-milestone levels), so each still pays at a sensible level. */
    @SuppressWarnings("unchecked")
    private static void loadChoices(forge.adventure.util.SaveFileData data, AscendanceState s) {
        if (data.containsKey("ascendancePendingLevels")) {
            List<Integer> levels = (List<Integer>) data.readObject("ascendancePendingLevels");
            if (levels != null)
                for (Integer l : levels)
                    if (l != null)
                        s.pendingLevels.add(l);
        } else if (data.containsKey("ascendancePending")) {
            int count = Math.max(0, data.readInt("ascendancePending"));
            List<Integer> rebuilt = new ArrayList<>();
            for (int l = levelFor(s.power); l >= 1 && rebuilt.size() < count; l--)
                if (!contains(data().lifeLevels, l) && !contains(data().titleLevels, l) && l <= curveTop())
                    rebuilt.add(0, l);
            s.pendingLevels.addAll(rebuilt);
        }
        if (data.containsKey("ascendanceOffer")) {
            List<String> offer = (List<String>) data.readObject("ascendanceOffer");
            if (offer != null)
                for (String id : offer)
                    if (id != null)
                        s.offer.add(id);
        }
        if (data.containsKey("ascendancePickIds") && data.containsKey("ascendancePickCounts")) {
            List<String> ids = (List<String>) data.readObject("ascendancePickIds");
            List<Integer> counts = (List<Integer>) data.readObject("ascendancePickCounts");
            if (ids != null && counts != null)
                for (int i = 0; i < Math.min(ids.size(), counts.size()); i++)
                    if (ids.get(i) != null && counts.get(i) != null)
                        s.picks.put(ids.get(i), counts.get(i));
        }
        if (data.containsKey("ascendanceHistory")) { // round 496: absent before it - the sheet starts empty
            List<String> history = (List<String>) data.readObject("ascendanceHistory");
            if (history != null)
                for (String entry : history)
                    if (entry != null)
                        s.history.add(entry);
        }
        s.vigorDay = data.containsKey("ascendanceVigorDay") ? data.readInt("ascendanceVigorDay") : -1;
        s.vigorUsed = data.containsKey("ascendanceVigorUsed") ? data.readInt("ascendanceVigorUsed") : 0;
        // Round 505: Power a dialog was holding when the game saved - the HUD pays it once the save is on screen.
        s.deferredPower = data.containsKey("ascendanceDeferredPower") ? Math.max(0, data.readInt("ascendanceDeferredPower")) : 0;
        s.deferredSource = data.containsKey("ascendanceDeferredSource") ? data.readString("ascendanceDeferredSource") : "";
        s.deferredLoaded = s.deferredPower > 0;
        s.rerolledLevel = data.containsKey("ascendanceRerolledLevel") ? data.readInt("ascendanceRerolledLevel") : 0; // round 518
    }

    // ------------------------------------------------------------------------------------------------ cheats

    /** Console "asc pick <id>": take that reward now, as if it had been offered (for testing each effect). */
    public static String cheatPick(String id) {
        AscendanceState s = state();
        if (s == null || !isActive())
            return "This character has no Ascendance (New Game or New Game+ only)";
        AscendanceData.Choice c = choice(id);
        if (c == null)
            return "No reward called " + id;
        if (s.pendingLevels.isEmpty())
            s.pendingLevels.add(level());
        s.offer.clear();
        s.offer.add(id);
        return choose(id);
    }

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
        int want = Math.max(0, Math.min(200, target));
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
                + "; companions " + companionsWorn(player) + "/" + companionAllowance() + "; choices waiting " + s.pendingLevels.size() + " " + s.pendingLevels + "; lasting " + s.picks
                + "; max life " + player.getMaxLife();
    }
}
