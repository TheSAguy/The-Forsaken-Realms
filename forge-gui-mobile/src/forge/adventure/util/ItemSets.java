package forge.adventure.util;

import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.Json;
import forge.adventure.data.EffectData;
import forge.adventure.data.ItemData;
import forge.adventure.data.ItemListData;
import forge.adventure.data.ItemSetData;
import forge.adventure.data.RoamingGuardData;
import forge.adventure.player.AdventurePlayer;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Round 629 - gear sets (design: the user's request "create gear sets and have you get additional bonuses when you have
 * these sets ... start at 3 out of 7 you get a small bonus, then it gets bigger the more you have", agreed in the
 * gear-sets proposal of 2026-10-15). Fifteen sets of seven in world/item_sets.json: five Regalia (one per color), five
 * Bane (each against one color, built from that color's two enemies) and five Legend.
 * <ul>
 * <li>A set counts the DISTINCT names of its members worn - a twin gauntlet slot cannot count one item twice - and a
 * cracked item is not worn (a lost duel takes it off).</li>
 * <li>Its bonus sits on top of the items' own effects: 3 pieces +2 life (a Bane set +3, only against its color), 5 the
 * set's card in the command zone, 7 that card's full version in its place. Never a creature (the companion limit stays
 * the items' own).</li>
 * <li>The player's duels (DuelScene) and a roaming guard's, watched or simulated (ArmoryStorage.effectsOf), take the
 * same bonus.</li>
 * </ul>
 */
public final class ItemSets {
    private static final String FILE = "world/item_sets.json";
    private static ItemSetData[] sets;
    private static final Map<String, ItemSetData> byItem = new LinkedHashMap<>();

    private ItemSets() {
    }

    /** Every set of this plane (none on a plane without the file). */
    public static synchronized ItemSetData[] all() {
        if (sets == null) {
            sets = new ItemSetData[0];
            byItem.clear();
            FileHandle file = Config.instance().getFile(FILE);
            if (file != null && file.exists()) {
                try {
                    @SuppressWarnings("unchecked")
                    Array<ItemSetData> read = new Json().fromJson(Array.class, ItemSetData.class, file);
                    sets = read.toArray(ItemSetData.class);
                    for (ItemSetData set : sets)
                        for (String item : set.items)
                            byItem.put(item.toLowerCase(Locale.ROOT), set);
                    System.out.println("[TFR-Sets] " + sets.length + " gear sets, " + byItem.size() + " items");
                } catch (Exception e) {
                    System.err.println("[TFR-Sets] " + FILE + " failed to load - no gear sets: " + e);
                    sets = new ItemSetData[0];
                    byItem.clear();
                }
            }
        }
        return sets;
    }

    /** The set an item belongs to, or null. */
    public static ItemSetData setOf(String itemName) {
        all();
        return itemName == null ? null : byItem.get(itemName.toLowerCase(Locale.ROOT));
    }

    /** Pieces of each set worn: distinct uncracked names. Sets with none are left out. */
    public static Map<ItemSetData, Integer> worn(Iterable<ItemData> items) {
        Map<ItemSetData, Set<String>> names = new LinkedHashMap<>();
        if (items != null)
            for (ItemData item : items) {
                if (item == null || item.isCracked)
                    continue;
                ItemSetData set = setOf(item.name);
                if (set != null)
                    names.computeIfAbsent(set, s -> new HashSet<>()).add(item.name.toLowerCase(Locale.ROOT));
            }
        Map<ItemSetData, Integer> out = new LinkedHashMap<>();
        for (Map.Entry<ItemSetData, Set<String>> e : names.entrySet())
            out.put(e.getKey(), e.getValue().size());
        return out;
    }

    /** The tiers a set reaches with this many pieces, a replaced tier dropped (the 7-piece card stands in for the 5's). */
    public static List<ItemSetData.Tier> reached(ItemSetData set, int pieces) {
        List<ItemSetData.Tier> out = new ArrayList<>();
        for (ItemSetData.Tier tier : set.tiers)
            if (pieces >= tier.pieces)
                out.add(tier);
        List<ItemSetData.Tier> replacedOut = new ArrayList<>(out);
        for (ItemSetData.Tier tier : out)
            if (tier.replaces > 0)
                replacedOut.removeIf(t -> t.pieces == tier.replaces);
        return replacedOut;
    }

    /** True when a duel's opponent colors (an EnemyData colors string, e.g. "BR") include the Bane set's target color. */
    public static boolean isAgainst(ItemSetData set, String opponentColors) {
        return set.against != null && opponentColors != null
                && opponentColors.toUpperCase(Locale.ROOT).contains(set.against.toUpperCase(Locale.ROOT));
    }

    /**
     * The duel effects the worn sets add, for whoever wears them ({@code who} names them in the log). A Bane tier marked
     * onlyAgainst counts only when {@code opponentColors} include its color.
     */
    public static Array<EffectData> bonusEffects(Iterable<ItemData> items, String opponentColors, String who) {
        Array<EffectData> out = new Array<>();
        for (Map.Entry<ItemSetData, Integer> e : worn(items).entrySet()) {
            ItemSetData set = e.getKey();
            int pieces = e.getValue();
            List<String> parts = new ArrayList<>();
            for (ItemSetData.Tier tier : reached(set, pieces)) {
                if (tier.effect == null)
                    continue;
                if (tier.onlyAgainst && !isAgainst(set, opponentColors)) {
                    parts.add("(" + tier.text + ": not against this foe)");
                    continue;
                }
                out.add(tier.effect);
                parts.add(tier.text);
            }
            if (pieces >= 3 || !parts.isEmpty())
                System.out.println("[TFR-Sets] " + who + ": " + set.name + " " + pieces + "/" + set.items.length
                        + (parts.isEmpty() ? " - no bonus yet" : " -> " + String.join(", ", parts)));
        }
        return out;
    }

    /** The player's worn items, as DuelScene collects them. */
    public static List<ItemData> wornBy(AdventurePlayer player) {
        List<ItemData> out = new ArrayList<>();
        if (player == null)
            return out;
        for (Long id : player.getEquippedItems()) {
            ItemData item = player.getEquippedItem(id);
            if (item != null)
                out.add(item);
        }
        return out;
    }

    /** Whether the player holds an item of this name: carried, in the Armory storage, or worn by a roaming guard. */
    public static boolean owns(AdventurePlayer player, String itemName) {
        if (player == null || itemName == null)
            return false;
        if (player.hasItem(itemName))
            return true;
        for (ItemData item : player.getArmoryStorage())
            if (item != null && itemName.equalsIgnoreCase(item.name))
                return true;
        for (RoamingGuardData guard : RoamingGuards.roster())
            for (ItemData item : guard.equipment)
                if (item != null && itemName.equalsIgnoreCase(item.name))
                    return true;
        return false;
    }

    // ------------------------------------------------------------------ round 631: what the screens say

    private static final String[] COLOR_LETTERS = {"W", "U", "B", "R", "G"};
    private static final String[] COLOR_WORDS = {"white", "blue", "black", "red", "green"};

    /** "white" for "W". */
    public static String colorWord(String letter) {
        for (int i = 0; i < COLOR_LETTERS.length; i++)
            if (COLOR_LETTERS[i].equalsIgnoreCase(letter))
                return COLOR_WORDS[i];
        return letter == null ? "" : letter;
    }

    /** "Regalia - white", "Bane - against white", "Legend". */
    public static String familyLine(ItemSetData set) {
        if (set.color != null && !set.color.isEmpty())
            return set.family + " - " + set.color;
        if (set.against != null && !set.against.isEmpty())
            return set.family + " - against " + colorWord(set.against);
        return set.family;
    }

    /** Where a piece is found, from the set's {@code itemWhere} (empty when the file has none). */
    public static String whereToFind(ItemSetData set, String itemName) {
        for (int i = 0; i < set.items.length && i < set.itemWhere.length; i++)
            if (set.items[i].equalsIgnoreCase(itemName))
                return set.itemWhere[i];
        return "";
    }

    /**
     * Where the player's copy of a set piece is, or null when they own none: worn by {@code guard} (the guard's page),
     * worn by the player, in the bag, in the Armory storage, or worn by one of their roaming guards.
     */
    public static String locate(AdventurePlayer player, RoamingGuardData guard, String itemName) {
        if (player == null || itemName == null)
            return null;
        if (guard != null)
            for (ItemData item : guard.equipment)
                if (item != null && itemName.equalsIgnoreCase(item.name))
                    return "worn by this guard";
        for (ItemData item : wornBy(player))
            if (itemName.equalsIgnoreCase(item.name))
                return item.isCracked ? "worn, but cracked" : "worn by you";
        for (ItemData item : player.getItems())
            if (item != null && itemName.equalsIgnoreCase(item.name))
                return item.isCracked ? "in your bag, cracked" : "in your bag";
        for (ItemData item : player.getArmoryStorage())
            if (item != null && itemName.equalsIgnoreCase(item.name))
                return "in the Armory storage";
        for (RoamingGuardData other : RoamingGuards.roster())
            if (other != guard)
                for (ItemData item : other.equipment)
                    if (item != null && itemName.equalsIgnoreCase(item.name))
                        return "worn by your " + RoamingGuards.displayName(other.tier) + " guard";
        return null;
    }

    /** Round 637 (the user: "only have the key/icon show up when you wear one of the set pieces"): whether these worn
     *  items hold a piece of any set (cracked pieces are off and do not count). */
    public static boolean wearsAnySet(Iterable<ItemData> items) {
        return !worn(items).isEmpty();
    }

    /** How many of a set's pieces the player owns anywhere (distinct names). */
    public static int ownedCount(AdventurePlayer player, ItemSetData set) {
        int n = 0;
        for (String item : set.items)
            if (owns(player, item))
                n++;
        return n;
    }

    /**
     * The line an item's description gains when it belongs to a set (shops, rewards and the inventory alike): the set,
     * its family, and how many of its pieces the player wears now.
     */
    public static String descriptionLine(ItemData item) {
        ItemSetData set = item == null ? null : setOf(item.name);
        if (set == null)
            return "";
        AdventurePlayer player = Current.player();
        if (player == null)
            return "";
        Integer worn = worn(wornBy(player)).get(set);
        if (worn != null)
            return "Gear set: " + set.name + " (" + familyLine(set) + ") - you wear " + worn + " of " + set.items.length;
        // Round 637 (the user: "other set info will be hidden unless you wear a piece of a set"): a set nobody of the
        // player's wears stays unnamed - in shops and rewards too.
        for (RoamingGuardData guard : RoamingGuards.roster()) {
            Integer guardWorn = worn(guard.equipment).get(set);
            if (guardWorn != null)
                return "Gear set: " + set.name + " (" + familyLine(set) + ") - your " + RoamingGuards.displayName(guard.tier)
                        + " guard wears " + guardWorn + " of " + set.items.length;
        }
        return "";
    }

    /**
     * An Arena prize that is a set piece the player already owns is swapped for a piece of the same set from the same
     * prize list that they do not own yet, while there is one - so the Arena finishes a set instead of repeating it.
     */
    public static void preferUnownedSetPieces(Array<Reward> rolled, String[] pool, Random random) {
        if (rolled == null || pool == null || all().length == 0)
            return;
        AdventurePlayer player = Current.player();
        for (int i = 0; i < rolled.size; i++) {
            Reward reward = rolled.get(i);
            if (reward == null || reward.getType() != Reward.Type.Item || reward.getItem() == null)
                continue;
            String name = reward.getItem().name;
            ItemSetData set = setOf(name);
            if (set == null || !owns(player, name))
                continue;
            List<String> missing = new ArrayList<>();
            for (String candidate : pool)
                if (setOf(candidate) == set && !owns(player, candidate) && ItemListData.getItem(candidate) != null)
                    missing.add(candidate);
            if (missing.isEmpty())
                continue;
            String pick = missing.get(random.nextInt(missing.size()));
            rolled.set(i, new Reward(ItemListData.getItem(pick)));
            System.out.println("[TFR-Sets] Arena prize: " + name + " is already owned - " + pick + " instead ("
                    + set.name + ", " + missing.size() + " piece(s) still missing)");
        }
    }
}
