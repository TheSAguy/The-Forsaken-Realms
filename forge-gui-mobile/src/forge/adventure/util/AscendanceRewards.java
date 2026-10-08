package forge.adventure.util;

import com.badlogic.gdx.utils.Array;
import forge.Forge;
import forge.adventure.data.ItemData;
import forge.adventure.data.ItemListData;
import forge.adventure.data.RewardData;
import forge.adventure.data.RoamingGuardData;
import forge.adventure.player.AdventurePlayer;
import forge.adventure.pointofintrest.PointOfInterest;
import forge.adventure.pointofintrest.PointOfInterestChanges;
import forge.adventure.scene.RewardScene;
import forge.adventure.world.World;
import forge.adventure.world.WorldSave;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Round 494 - Ascendance's one-time rewards (the pick-1-of-3 on a level that is not a milestone): what each id is
 * called, what it would give at a level, whether it can give anything at all, and the giving. Amounts scale with the
 * level the choice was earned at (ascendance.json "value" x level). Cards, an item, a booster and a map piece are turned
 * over on the reward screen like any loot; resources, reputation and repairs are paid on the spot.
 */
final class AscendanceRewards {
    private AscendanceRewards() {
    }

    private static final Random RAND = new Random();

    static String name(String id) {
        switch (id) {
            case "gold": return "Gold";
            case "shards": return "Shards";
            case "wood": return "Wood";
            case "stone": return "Stone";
            case "rareCards": return "Rare cards";
            case "item": return "An item";
            case "booster": return "A booster pack";
            case "mapFragment": return "A map fragment";
            case "blueprint": return "A blueprint";
            case "coins": return "Bronze Coins";
            case "goodwill": return "Goodwill";
            case "mend": return "Mend";
            default: return id;
        }
    }

    private static int amount(float value, int level) {
        return Math.max(1, Math.round(value * level));
    }

    private static String itemRarity(int level) {
        return level >= 23 ? "Rare" : level >= 13 ? "Uncommon" : "Common";
    }

    static String describe(String id, float value, int level) {
        switch (id) {
            case "gold": return "+" + amount(value, level) + " [+Gold]";
            case "shards": return "+" + amount(value, level) + " [+Shards]";
            case "wood": return "+" + amount(value, level) + " [+Wood]";
            case "stone": return "+" + amount(value, level) + " [+Stone]";
            case "rareCards": return level >= 23 ? "A rare and a mythic in your deck's colors"
                    : level >= 13 ? "Two rares in your deck's colors" : "A rare in your deck's colors";
            case "item": return "A random " + itemRarity(level).toLowerCase() + " item";
            case "booster": return "A pack of an edition you have unlocked";
            case "mapFragment": return "[+MapFragmentWaste] A piece of a treasure map you are still missing";
            case "blueprint": return "A shop blueprint you don't have yet";
            case "coins": return "+" + Math.round(value) + " [+BronzeChallengeCoin] Bronze Challenge Coins";
            case "goodwill": PointOfInterest town = leastRespectedTown();
                return "+" + Math.round(value) + " reputation in " + (town == null ? "your town" : town.getDisplayName());
            case "mend": return "Repair every cracked item; downed roaming guards back on their feet";
            default: return id;
        }
    }

    /** False when this reward has nothing to give now - it is not offered then. */
    static boolean canGive(String id, int level) {
        switch (id) {
            case "gold": case "shards": case "wood": case "stone": case "rareCards": case "coins":
                return true;
            case "item": return !ItemListData.getItemNamesByRarity(itemRarity(level)).isEmpty();
            case "booster": return !boosterEditions().isEmpty();
            case "mapFragment": return !regionsMissingPieces().isEmpty();
            case "blueprint": return !unknownBlueprints().isEmpty();
            case "goodwill": return leastRespectedTown() != null;
            case "mend": return !crackedItems().isEmpty() || !downedGuards().isEmpty();
            default: return false;
        }
    }

    /** Pay it; the text for the toast. */
    static String give(String id, float value, int level) {
        AdventurePlayer player = WorldSave.getCurrentSave().getPlayer();
        Array<Reward> loot = new Array<>();
        String result;
        switch (id) {
            // Round 496 (the user: "All rewards should use the Icon symbols").
            case "gold": player.giveGold(amount(value, level)); result = "+" + amount(value, level) + " [+Gold]"; break;
            case "shards": player.addShards(amount(value, level)); result = "+" + amount(value, level) + " [+Shards]"; break;
            case "wood": player.addWood(amount(value, level)); result = "+" + amount(value, level) + " [+Wood]"; break;
            case "stone": player.addStone(amount(value, level)); result = "+" + amount(value, level) + " [+Stone]"; break;
            case "rareCards":
                if (level >= 23) {
                    loot.addAll(cards(1, "Rare"));
                    loot.addAll(cards(1, "Mythic Rare"));
                } else {
                    loot.addAll(cards(level >= 13 ? 2 : 1, "Rare"));
                }
                result = loot.size + " card" + (loot.size == 1 ? "" : "s");
                break;
            case "item": {
                List<String> names = ItemListData.getItemNamesByRarity(itemRarity(level));
                ItemData item = names.isEmpty() ? null : ItemListData.getItem(names.get(RAND.nextInt(names.size())));
                if (item != null)
                    loot.add(new Reward(item));
                result = item == null ? "no item" : item.name;
                break;
            }
            case "booster": {
                List<String> editions = boosterEditions();
                String code = editions.isEmpty() ? null : editions.get(RAND.nextInt(editions.size()));
                forge.deck.Deck pack = code == null ? null : AdventureEventController.instance().generateBooster(code);
                if (pack != null)
                    loot.add(new Reward(pack));
                result = pack == null ? "no pack" : "a " + code + " pack";
                break;
            }
            case "mapFragment": {
                List<Integer> regions = regionsMissingPieces();
                int r = regions.isEmpty() ? -1 : regions.get(RAND.nextInt(regions.size()));
                ItemData fragment = r < 0 ? null : ItemListData.getItem(TreasureHunt.fragmentItemName(r));
                if (fragment != null)
                    loot.add(new Reward(fragment));
                result = fragment == null ? "no fragment" : "[+" + fragment.iconName + "] " + fragment.name;
                break;
            }
            case "blueprint":
                // ResourceSpawns' own grant: an unknown type, unlocked at once, then turned over on the reward screen.
                result = ResourceSpawns.grantRandomBlueprint("Ascendance level " + level) ? "a blueprint" : "no blueprint left";
                break;
            case "coins":
                for (int i = 0; i < Math.round(value); i++) {
                    ItemData coin = ItemListData.getItem(AdventurePlayer.BRONZE_COIN_ITEM);
                    if (coin != null)
                        loot.add(new Reward(coin));
                }
                result = "+" + loot.size + " [+BronzeChallengeCoin] Bronze Coins";
                break;
            case "goodwill": {
                PointOfInterest town = leastRespectedTown();
                if (town == null) {
                    result = "no town to favor";
                    break;
                }
                PointOfInterestChanges changes = WorldSave.getCurrentSave().getPointOfInterestChanges(town.getID());
                changes.addMapReputation(Math.round(value));
                result = "+" + Math.round(value) + " reputation in " + town.getDisplayName() + " (now " + changes.getMapReputation() + ")";
                break;
            }
            case "mend": {
                List<ItemData> cracked = crackedItems();
                for (ItemData item : cracked)
                    item.isCracked = false;
                List<RoamingGuardData> downed = downedGuards();
                for (RoamingGuardData guard : downed)
                    guard.downUntilDay = 0;
                result = cracked.size() + " item(s) repaired, " + downed.size() + " guard(s) healed";
                break;
            }
            default: result = id;
        }
        if (loot.size > 0) { // turned over and collected on the reward screen, like any loot (it grants on Done)
            RewardScene.instance().loadRewards(loot, RewardScene.Type.Loot, null);
            Forge.switchScene(RewardScene.instance());
        }
        return result;
    }

    /** {@code count} cards of {@code rarity} in the player's colors (RewardData's "colorID"). */
    private static Array<Reward> cards(int count, String rarity) {
        RewardData data = new RewardData();
        data.type = "card";
        data.count = count;
        data.probability = 1;
        data.rarity = new String[]{rarity};
        data.colors = new String[]{"colorID"};
        return data.generate(false, true);
    }

    /** Unlocked editions a pack can be made of (Jumpstart and other template-less sets cannot). */
    private static List<String> boosterEditions() {
        List<String> out = new ArrayList<>();
        for (String code : WorldSave.getCurrentSave().getPlayer().getUnlockedEditions()) {
            forge.card.CardEdition edition = forge.model.FModel.getMagicDb().getEditions().get(code);
            if (edition != null && edition.hasBoosterTemplate())
                out.add(code);
        }
        return out;
    }

    /** Treasure regions still short of pieces - counting the fragments already in the bag, unused. */
    private static List<Integer> regionsMissingPieces() {
        List<Integer> out = new ArrayList<>();
        World world = WorldSave.getCurrentSave().getWorld();
        if (world == null || !TreasureHunt.isEnabled())
            return out;
        AdventurePlayer player = WorldSave.getCurrentSave().getPlayer();
        for (int r = 0; r < TreasureHunt.REGION_NAMES.length; r++) {
            int[] h = TreasureHunt.hunt(world, r);
            if (h == null || h[TreasureHunt.H_FOUND] != 0)
                continue;
            if (h[TreasureHunt.H_FRAGS] + player.countItem(TreasureHunt.fragmentItemName(r)) < TreasureHunt.FRAGMENTS)
                out.add(r);
        }
        return out;
    }

    private static List<String> unknownBlueprints() {
        List<String> out = new ArrayList<>();
        if (!Config.instance().getConfigData().shopBlueprintsEnabled)
            return out;
        for (String name : EconomyBuildings.allChooserShopNames())
            if (!EconomyBuildings.isShopTypeUnlocked(name))
                out.add(name);
        return out;
    }

    /** The user: "Goodwill should be for your own towns only" - the restored or captured town (the Capitol included) with
     *  the least reputation. */
    private static PointOfInterest leastRespectedTown() {
        PointOfInterest best = null;
        int bestRep = Integer.MAX_VALUE;
        for (PointOfInterest poi : WorldSave.getCurrentSave().getWorld().getAllPointOfInterest()) {
            PointOfInterestChanges changes = WorldSave.getCurrentSave().peekPointOfInterestChanges(poi.getID());
            if (!TownRestoration.isTownRestored(changes))
                continue;
            int rep = changes.getMapReputation();
            if (rep < bestRep) {
                bestRep = rep;
                best = poi;
            }
        }
        return best;
    }

    private static List<ItemData> crackedItems() {
        List<ItemData> out = new ArrayList<>();
        for (ItemData item : WorldSave.getCurrentSave().getPlayer().getItems())
            if (item != null && item.isCracked)
                out.add(item);
        return out;
    }

    private static List<RoamingGuardData> downedGuards() {
        List<RoamingGuardData> out = new ArrayList<>();
        int day = WorldSave.getCurrentSave().getWorld().getCurrentDay();
        for (RoamingGuardData guard : RoamingGuards.roster())
            if (guard.isOutOfCommission(day))
                out.add(guard);
        return out;
    }
}
