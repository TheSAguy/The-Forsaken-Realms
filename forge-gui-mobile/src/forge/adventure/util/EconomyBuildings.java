package forge.adventure.util;

import com.badlogic.gdx.graphics.g2d.Sprite;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.ui.CheckBox;
import com.badlogic.gdx.scenes.scene2d.ui.Cell;
import com.badlogic.gdx.scenes.scene2d.ui.Dialog;
import com.badlogic.gdx.scenes.scene2d.ui.Image;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener;
import com.badlogic.gdx.scenes.scene2d.utils.TextureRegionDrawable;
import com.badlogic.gdx.utils.Array;
import com.github.tommyettinger.textra.TextraButton;
import com.github.tommyettinger.textra.TypingLabel;
import forge.Forge;
import forge.adventure.character.ShopActor;
import forge.adventure.data.DialogData;
import forge.adventure.data.DifficultyData;
import forge.adventure.data.ItemData;
import forge.adventure.data.ItemListData;
import forge.adventure.data.RewardData;
import forge.adventure.data.ShopData;
import forge.adventure.data.TuningData;
import forge.adventure.player.AdventurePlayer;
import forge.adventure.scene.RewardScene;
import forge.adventure.scene.UIScene;
import forge.adventure.pointofintrest.PointOfInterest;
import forge.adventure.pointofintrest.PointOfInterestChanges;
import forge.adventure.stage.GameHUD;
import forge.adventure.stage.MapStage;
import forge.adventure.stage.WorldStage;
import forge.adventure.world.WorldSave;
import forge.gui.FThreads;
import forge.item.PaperCard;
import forge.screens.CoverScreen;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Economy buildings (MOD_SCOPE.md #9): wasteland shops can optionally be rebuilt as one of six
 * production/finance buildings instead of a normal Card Shop. Only one of these six is allowed
 * per town (Card Shop rebuilds have no such limit) - enforced declaratively via the
 * ECONOMY_TYPE_FLAG map flag, same mechanism TownRestoration already uses for shopRebuilt_<id>.
 */
public class EconomyBuildings {
    public static final int NONE = 0;
    public static final int SHARD_MINE = 1;
    public static final int GOLD_MINE = 2;
    public static final int LUMBER_MILL = 3;
    public static final int STONE_MINE = 4;
    public static final int BANK = 5;
    public static final int EXCHANGE = 6;
    // Outlook (vision) + Teleporter (fast travel), added 2026-08-09. Same one-per-town machinery
    // as the original 6 - see buildOption()/builtFlag() - just two more type ids.
    public static final int OUTLOOK = 7;
    public static final int TELEPORTER = 8;
    // Archaeologist (2026-08-11) - moved from a standalone Tiled map object to a Utility-submenu
    // economy building (user request), same one-per-town machinery, but Capitol-only (see
    // buildChooseBuildingDialog()'s isCapitol gate) - the expedition-timer field it drives
    // (PointOfInterestChanges.archaeologistExpeditionSentDay) is a single, non-objectId-keyed
    // field by design, "never more than one per save".
    public static final int ARCHAEOLOGIST = 9;
    // Trader (2026-08-22, user spec): a Financial building offering Wood/Stone trades like
    // Exchange but at worse rates (25% pricier to buy, 25% less on sell - see TRADER_TRADES) and,
    // unlike Bank/Exchange, buildable in an ordinary town, not just the Capitol - meant as an
    // early/accessible way to convert gold into resources before Exchange is reachable. A Trader
    // in the Capitol can be upgraded into an Exchange - see openUpgradeToExchangeConfirmDialog()/
    // upgradeTraderToExchange() below.
    public static final int TRADER = 10;

    // Byte-safe map flag (0-6) used only to gate "one economy building per town" declaratively
    // and to discriminate which option the player picked in buildChooseBuildingDialog(). The
    // Tiled object id of the chosen shop is a separate, non-byte-limited field on
    // PointOfInterestChanges (economyBuildingObjectId) - see that class for why.
    public static final String ECONOMY_TYPE_FLAG = "economyBuildingType";

    // (Flat BUILD_COST retired 2026-08-12 - per-type multi-resource costs live in buildCostFor().)
    // (Flat RESOURCE_PRODUCTION_PER_DAY=5-for-every-type retired 2026-08-16 - per-resource weekly
    // amounts now live in TuningData, see mineWeeklyAmount() below.)
    private static final int INTEREST_PERIOD_DAYS = 7;
    private static final float INTEREST_RATE = 0.05f;

    private static final String ATLAS = "maps/tileset/economy_buildings.atlas";
    // Real art for the newer building types (2026-08-09): 16x16 tiles the user pinpointed in
    // common's buildings.png via Tiled's tile inspector (Look-out 355, Teleporter 528, Arena 227),
    // extracted + 2x nearest-upscaled to the same 32x32 the six economy_buildings icons use.
    // Science Lab 805 was speculatively reserved here too but never built - still unclaimed.
    // Archaeologist 751 (also speculatively reserved here) was checked on 2026-08-11 when the
    // Archaeologist building actually got built: on inspection it's part of an unrelated teal
    // guardian-temple sprite, not archaeology-themed at all, so it was NOT used - see
    // getArchaeologistSprite() below, which uses the generic SpecialShop icon as a placeholder.
    private static final String NEW_BUILDINGS_ATLAS = "maps/tileset/new_buildings.atlas";
    // Guard tier map-indicator icons (2026-08-11, MOD_SCOPE.md #22) - 8x8, cropped from
    // common/maps/tileset/dungeon.png (user-supplied IDs 83/84/86/88) and shrunk per the user's
    // own mockup estimate. See PointOfInterestMapSprite for where these actually get drawn.
    private static final String GUARD_ICONS_ATLAS = "maps/tileset/guard_icons.atlas";

    private EconomyBuildings() {}

    /** The strongest currently-hired guard's tier at this POI, or null if it has none - used by
     *  PointOfInterestMapSprite for the overworld indicator icon (shows one icon even when the
     *  Capitol has 2 guards, same "strongest represents the defense" simplification the combat
     *  resolution itself uses for fight order). */
    public static String strongestGuardTier(PointOfInterestChanges changes) {
        if (changes == null || changes.getGuardCount() == 0)
            return null;
        String strongest = changes.getGuardTier(0);
        for (int i = 1; i < changes.getGuardCount(); i++) {
            String candidate = changes.getGuardTier(i);
            if (indexOfTier(candidate) > indexOfTier(strongest))
                strongest = candidate;
        }
        return strongest;
    }

    private static int indexOfTier(String tier) {
        for (int i = 0; i < GUARD_TIERS_ASCENDING.length; i++) {
            if (GUARD_TIERS_ASCENDING[i].equals(tier))
                return i;
        }
        return 0;
    }

    public static TextureRegion getGuardTierIconSprite(String tier) {
        String region;
        switch (tier == null ? "" : tier) {
            case "Uncommon": region = "GuardAdept"; break;
            case "Rare": region = "GuardMaster"; break;
            case "Mythic": region = "GuardChallenger"; break;
            default: region = "GuardApprentice"; break;
        }
        return Config.instance().getAtlasSprite(GUARD_ICONS_ATLAS, region);
    }

    public static String buildingName(int type) {
        switch (type) {
            case SHARD_MINE: return "Shard Mine";
            case GOLD_MINE: return "Gold Mine";
            case LUMBER_MILL: return "Lumber Mill";
            case STONE_MINE: return "Stone Mine";
            case BANK: return "Bank";
            case EXCHANGE: return "Exchange";
            case OUTLOOK: return "Outlook";
            case TELEPORTER: return "Teleporter";
            case ARCHAEOLOGIST: return "Archaeologist";
            case TRADER: return "Trader";
            default: return "Card Shop";
        }
    }

    private static String atlasRegion(int type) {
        switch (type) {
            case SHARD_MINE: return "ShardMine";
            case GOLD_MINE: return "GoldMine";
            case LUMBER_MILL: return "LumberMill";
            case STONE_MINE: return "StoneMine";
            case BANK: return "Bank";
            case EXCHANGE: return "Exchange";
            // TELEPORTER deliberately excluded - it needs the animated portal art, handled by
            // ShopActor directly via getTeleporterClosedSprite()/getTeleporterActiveAnimation(),
            // not this single-static-region path.
            default: return null;
        }
    }

    /**
     * Icon to draw over a rebuilt shop's normal footprint when it's the town's registered economy
     * building, or null if it isn't one (a plain or special shop - see getPlainShopSprite()/
     * getSpecialShopSprite()), OR if it's TELEPORTER (animated - see ShopActor.draw(), which
     * intercepts that type before ever calling this method).
     */
    public static TextureRegion getBuildingSprite(int type) {
        if (type == OUTLOOK)
            return Config.instance().getAtlasSprite(NEW_BUILDINGS_ATLAS, "Outlook");
        if (type == ARCHAEOLOGIST)
            return getArchaeologistSprite();
        if (type == TRADER)
            return Config.instance().getAtlasSprite(NEW_BUILDINGS_ATLAS, "Trader");
        String region = atlasRegion(type);
        if (region == null)
            return null;
        return Config.instance().getAtlasSprite(ATLAS, region);
    }

    /** Rebuilt-Arena icon for the Capitol's gated Arena building (see OnCollide.draw()) - level 2
     *  (paid upgrade, Task #8) keeps the original art, level 1 (default/base) uses the smaller
     *  landscape (32x16) art alongside it. */
    public static TextureRegion getArenaSprite(int level) {
        return Config.instance().getAtlasSprite(NEW_BUILDINGS_ATLAS, level >= 2 ? "Arena" : "ArenaLevel1");
    }

    /** Real Spellsmith art (buildings.png IDs 432/433/460/461, a 2x2 block) - added to
     *  new_buildings.atlas in an earlier round, but MapStage's "spellsmith" case was never
     *  actually updated to call this and kept using the generic SpecialShop placeholder instead
     *  (found 2026-08-11 - the art was correct, the wiring wasn't; see MapStage.java's case). */
    public static TextureRegion getSpellsmithSprite() {
        return Config.instance().getAtlasSprite(NEW_BUILDINGS_ATLAS, "Spellsmith");
    }

    // Placeholder cost per user spec 2026-08-11 ("some 100g for now") - shared by every building
    // upgrade (Arena today; Armory once its level 1 art and Guard-hiring mechanic land, Task #13).
    // 2026-08-12 user cost table: the two building upgrades cost resources, not gold.
    // Wood/Stone components halved 2026-08-21 (v1.00 feedback: "the wood/stone numbers seem way
    // too high... upgrading even one town is a pain") - gold/shard components untouched.
    public static final int ARMORY_UPGRADE_STONE = 150;
    public static final int ARENA_UPGRADE_STONE = 150;
    public static final int ARENA_UPGRADE_WOOD = 150;

    // Armory manual "Re-roll" button (2026-08-11, round 7 - user spec: "cost 100 shards base"),
    // independent of both the automatic weekly refresh and the ordinary (Armory-blocked, since
    // Armory is a noRestock shop) paid restock button. Difficulty-scaled like every other cost via
    // scaledCost() - see RewardScene.promptRerollArmory().
    public static final int ARMORY_REROLL_SHARD_COST = 100;

    // Card Shop Type Re-Roll (2026-08-11, round 8, user spec: "add a re-roll card shop type for
    // 50 shards"). Difficulty-scaled like every other cost via scaledCost() - see
    // RewardScene.promptRerollShopType()/MapStage.rerollShopType().
    public static final int SHOP_TYPE_REROLL_SHARD_COST = 50;

    // (Ante Re-roll's own base cost/escalation rate migrated 2026-08-16 into TuningData -
    // anteRerollBaseShardCost/anteRerollEscalationRate - see MatchController.revealAnteCards().)

    // Difficulty price multiplier (user spec 2026-08-11, round 4): building repair/construction/
    // upgrade costs and guard hiring costs scale with difficulty - Easy 25% cheaper, Normal
    // baseline, Hard 25% more, Insane 50% more. Deliberately NOT applied to card/item shop prices
    // (ShopActor.getPriceModifier() already has its own reputation-tier scaling, #1) or the
    // Exchange/Bank resource trades (symmetric buy/sell, not a one-directional "cost"). Same
    // index-lookup pattern as World.visionRadiusDifficultyOffset()/TerritoryControl.
    // maxActiveMagesPerColor() - the plane's own config.json defines exactly 4 tiers, confirmed
    // directly (Easy/Normal/Hard/Insane, in that order) rather than assumed, so a flat linear step
    // of +0.25 per index lands exactly on the user's 4 numbers (0.75/1.00/1.25/1.50).
    public static float difficultyPriceMultiplier() {
        DifficultyData playerDifficulty = AdventurePlayer.current().getDifficulty();
        DifficultyData[] allDifficulties = Config.instance().getConfigData().difficulties;
        if (playerDifficulty == null || playerDifficulty.name == null || allDifficulties == null)
            return 1f;
        for (int i = 0; i < allDifficulties.length; i++) {
            if (playerDifficulty.name.equals(allDifficulties[i].name))
                return 0.75f + 0.25f * i;
        }
        return 1f;
    }

    public static int scaledCost(int baseCost) {
        return Math.round(baseCost * difficultyPriceMultiplier());
    }

    // Arena entry (2026-08-11, Task #8/#20): originally a pre-entry MapStage gating dialog
    // (Enter Arena/Enter Challenge Arena/Upgrade), replaced the same day by the user's own
    // follow-up request to move Upgrade + a Normal/Challenging toggle INTO the Arena screen
    // itself instead - see ArenaScene.enterArenaBuilding()/promptUpgradeArena()/
    // toggleArenaMode(), and MapStage's "arena" case for the new (much simpler) collision hook.

    // ---- Archaeologist (2026-08-11, user spec) ----
    // Capitol-only building - moved from a standalone Tiled map object to a Utility-submenu
    // economy building (2026-08-11, user request: "I don't want a stand alone building... put it
    // under the Utility sub menu so it can be built on one of the pre-existing destroyed building
    // spots"), same one-per-town machinery as Outlook/Teleporter (see ARCHAEOLOGIST above/
    // buildOption()/builtFlag()). Sends an expedition that returns 5 cards the player doesn't
    // already own (by name - see ownedCardNames() below), no Mythic Rare, one from each of 5
    // different sets (user spec 2026-08-11), plus a 25% chance of an additional booster pack and
    // a 5% chance of an additional non-Mythic item.
    public static final int ARCHAEOLOGIST_EXPEDITION_DAYS = 7;
    private static final float ARCHAEOLOGIST_BOOSTER_CHANCE = 0.25f;
    private static final float ARCHAEOLOGIST_ITEM_CHANCE = 0.05f;
    // User spec 2026-08-11: "make it 1000g to send out an expedition" (was free).
    private static final int ARCHAEOLOGIST_EXPEDITION_COST = 1000;

    /** Real art (buildings.png IDs 722/723/750/751, a 2x2 block, user-specified 2026-08-11) -
     *  replaces the earlier placeholder (the generic SpecialShop icon, used while ID 751 alone
     *  looked unrelated - the full 2x2 block reads as a distinct, if guardian-statue-like,
     *  structure). */
    public static TextureRegion getArchaeologistSprite() {
        return Config.instance().getAtlasSprite(NEW_BUILDINGS_ATLAS, "Archaeologist");
    }

    public static void openArchaeologistDialog(MapStage stage, int objectId) {
        refreshArchaeologistDialog(stage, objectId);
        stage.showDialog();
    }

    private static void refreshArchaeologistDialog(MapStage stage, int objectId) {
        Dialog dialog = stage.getDialog();
        dialog.getContentTable().clear();
        dialog.getButtonTable().clear();
        dialog.clearListeners();

        PointOfInterestChanges changes = stage.getChanges();
        int sentDay = changes.getArchaeologistExpeditionSentDay();
        addContentRow(dialog, "Archaeologist");

        if (sentDay < 0) {
            addContentRow(dialog, "Send an expedition to dig up cards you don't yet own.");
            // Difficulty-scaled (round 4) - one local value shared by the affordability check,
            // the label, and the actual deduction below, so all three always agree.
            int cost = scaledCost(ARCHAEOLOGIST_EXPEDITION_COST);
            boolean canAfford = AdventurePlayer.current().getGold() >= cost;
            addButtonRow(dialog, "Send Expedition (" + cost + " [+Gold])", canAfford, () -> {
                AdventurePlayer.current().takeGold(cost);
                changes.setArchaeologistExpeditionSentDay(WorldSave.getCurrentSave().getWorld().getCurrentDay());
                refreshArchaeologistDialog(stage, objectId);
            });
        } else {
            int elapsed = WorldSave.getCurrentSave().getWorld().getCurrentDay() - sentDay;
            if (elapsed < ARCHAEOLOGIST_EXPEDITION_DAYS) {
                int daysLeft = ARCHAEOLOGIST_EXPEDITION_DAYS - elapsed;
                addContentRow(dialog, "Expedition in progress - " + daysLeft + (daysLeft == 1 ? " day" : " days") + " remaining.");
            } else {
                addContentRow(dialog, "The expedition has returned!");
                addButtonRow(dialog, "Collect Rewards", true, () -> {
                    changes.setArchaeologistExpeditionSentDay(-1);
                    stage.hideDialog();
                    Array<Reward> rewards = generateExpeditionRewards(WorldSave.getCurrentSave().getWorld().getRandom());
                    RewardScene.instance().loadRewards(rewards, RewardScene.Type.Loot, null);
                    Forge.switchScene(RewardScene.instance());
                });
            }
        }
        dialog.getButtonTable().add(Controls.newTextButton("Close", stage::hideDialog)).width(240f).row();
        dialog.setKeepWithinStage(true);
    }

    private static Set<String> ownedCardNames() {
        Set<String> owned = new HashSet<>();
        for (Map.Entry<PaperCard, Integer> entry : AdventurePlayer.current().getCards())
            owned.add(entry.getKey().getName());
        return owned;
    }

    private static Array<Reward> generateExpeditionRewards(Random random) {
        Array<Reward> rewards = new Array<>();

        RewardData nonMythic = new RewardData();
        nonMythic.rarity = new String[]{"Common", "Uncommon", "Rare"};
        List<PaperCard> pool = new ArrayList<>(CardUtil.getPredicateResult(RewardData.getAllCards(), nonMythic));
        Set<String> owned = ownedCardNames();
        pool.removeIf(pc -> owned.contains(pc.getName()));
        Collections.shuffle(pool, random);
        // User spec 2026-08-11: the 5 cards must come from 5 DIFFERENT expansions - greedily pick
        // in shuffled order, skipping any card whose edition is already represented, until 5
        // picks or the pool runs dry (hundreds of real editions exist, so running dry in practice
        // would mean the player already owns nearly every non-Mythic card in the game).
        Set<String> usedEditions = new HashSet<>();
        for (PaperCard pc : pool) {
            if (rewards.size >= 5)
                break;
            if (!usedEditions.add(pc.getEdition()))
                continue;
            rewards.add(new Reward(pc));
        }

        if (random.nextFloat() < ARCHAEOLOGIST_BOOSTER_CHANCE) {
            RewardData booster = new RewardData();
            booster.type = "cardPackShop";
            booster.probability = 1f;
            booster.count = 1;
            rewards.addAll(booster.generate(false, true));
        }

        if (random.nextFloat() < ARCHAEOLOGIST_ITEM_CHANCE) {
            String itemName = NON_MYTHIC_ITEM_POOL[random.nextInt(NON_MYTHIC_ITEM_POOL.length)];
            ItemData itemData = ItemListData.getItem(itemName);
            if (itemData != null)
                rewards.add(new Reward(itemData));
        }

        return rewards;
    }

    // Common+Uncommon+Rare, non-quest items from items.json (539 entries) - same query convention
    // (rarity + questItem exclusion) as the Arena Challenge item pools above, just spanning all
    // three non-Mythic tiers at once since the user's spec for this 5% roll wasn't tier-split. A
    // static snapshot, not a live query (unlike ItemListData.getItemNamesByRarity(), which this
    // array predates) - "Chandra's Stone"/"Liliana's Stone"/"Medal of Ultimate Victory" removed
    // 2026-08-13 (same trophy-item leak as the Armory's weighted sell pool, see
    // ItemData.excludeFromGeneralSale); this list can still drift from the live catalog since
    // nothing re-derives it automatically.
    private static final String[] NON_MYTHIC_ITEM_POOL = {
            "Landscape Sketchbook - Zendikar", "Landscape Sketchbook - Innistrad", "Landscape Sketchbook - Ravnica Allegiance", "Landscape Sketchbook - Theros", "Landscape Sketchbook - Kamigawa Neon Dynasty", "Landscape Sketchbook - Dominaria", "Landscape Sketchbook - Lorwyn", "Landscape Sketchbook - Shards of Alara", "Landscape Sketchbook - Amonkhet", "Landscape Sketchbook - Ixalan", "Landscape Sketchbook - Unglued", "Landscape Sketchbook - Unhinged", "Landscape Sketchbook - Unstable", "Landscape Sketchbook - Unfinity", "Landscape Sketchbook - Aetherdrift", "Landscape Sketchbook - Edge of Eternities", "Landscape Sketchbook - Strixhaven: School of Mages", "Landscape Sketchbook - Streets of New Capenna", "Landscape Sketchbook - Kaldheim", "Landscape Sketchbook - Ikoria: Lair of Behemoths", "Landscape Sketchbook - Throne of Eldraine", "Landscape Sketchbook - Shadows Over Innistrad", "Landscape Sketchbook - Battle for Zendikar", "Landscape Sketchbook - Khans of Tarkir", "Landscape Sketchbook - Hour of Devastation", "Landscape Sketchbook - Kaladesh", "Landscape Sketchbook - Mercadian Masques", "Landscape Sketchbook - New Phyrexia", "Landscape Sketchbook - Mirrodin Besieged", "Landscape Sketchbook - Scars of Mirrodin", "Landscape Sketchbook - Magic Origins", "Landscape Sketchbook - Magic 2021", "Landscape Sketchbook - Modern Horizons", "Landscape Sketchbook - Tempest", "Landscape Sketchbook - Onslaught", "Landscape Sketchbook - Odyssey", "Landscape Sketchbook - Rise of the Eldrazi", "Landscape Sketchbook - Dragons of Tarkir", "Landscape Sketchbook - Duskmourn: House of Horror", "Landscape Sketchbook - Outlaws of Thunder Junction", "Landscape Sketchbook - Bloomburrow", "Landscape Sketchbook - The Brothers War", "Landscape Sketchbook - Seventh Edition", "Landscape Sketchbook - Limited Edition Alpha", "Landscape Sketchbook - Limited Edition Beta", "Landscape Sketchbook - Revised Edition", "Landscape Sketchbook - Fourth Edition", "Landscape Sketchbook - Fifth Edition", "Landscape Sketchbook - Sixth Edition", "Landscape Sketchbook - Ice Age", "Landscape Sketchbook - Tempest", "Landscape Sketchbook - Portal", "Landscape Sketchbook - Portal Second Age", "Landscape Sketchbook - Portal Three Kingdoms", "Landscape Sketchbook - Starter 1999", "Landscape Sketchbook - Starter 2000", "Landscape Sketchbook - Battle Royale", "Landscape Sketchbook - Beatdown Box Set", "Landscape Sketchbook - Lorwyn Eclipsed", "Landscape Sketchbook - Tarkir: Dragonstorm", "Captive Soul of a Priest", "Kobold Boots", "Courier's Boots", "Symbiote Bondband", "Imposter's Sliverband", "Maestro Loafers", "Sky Skiff Turnkey", "Bloodfire Boots", "Witch's Shoes", "Petalmane Pants", "Enduring Sliverband", "Coiled Anklet", "Deathspore Shoes", "Obscura Shoes", "Angelic Ring", "Steel Boots", "Cabaretti Kicks", "Caravaneer's Greaves", "Silvergill Tailband", "Riveteer Greaves", "Deathspitter Skirt", "Lookout's Harness", "Bearhide Breeches", "Timebug Boots", "Glimmerbell Bondband", "Firefrightener Shoes", "Gingerboots", "Brokers Boots", "Scarecrow Socks", "Sabertooth Bondband", "Bandar Boots", "Trickster's Shoes", "Blistering Breeches", "Greaves of Glare", "Cragflame", "Fblthp's Lost Socks", "Raptor Bondband", "Plated Sliverband", "Greenseeker's Shoes", "Angelic Greaves", "Basilica Skullbomb", "Tome of Fire", "Golden Egg", "Malign Prayerbook", "Furnace Skullbomb", "Thaumaton Torpedo", "Scroll of Griselbrand", "Scroll of Avacyn", "Necrogen Gauntlet", "Aether Gauntlet", "Lotus Petal", "Pyrite Gauntlet", "Chromatic Sphere", "Amnesiac Prayerbook", "Kaleidostone", "Sunbeam Gauntlet", "Tome of the Executioner", "Maze Skullbomb", "Prayerbook of Vigor", "Surgical Skullbomb", "Tome of Removal", "Nascent Prayerbook", "Prayerbook of Return", "Tome of Dispelling", "Prayerbook of Ire", "Lifespark Gauntlet", "Volatile Prayerbook", "Prayerbook of Fortunes", "Wedding Invitation", "Dross Skullbomb", "Mephitic Draught", "Tome of Might", "Ephemeral Prayerbook", "Prayerbook of Fertility", "Ajani's Amulet", "Referee's Shoes", "Bladed Bracer", "Echo Shield", "Runed Stalactite", "Tawnos's Wand", "Short Sword", "Kor Halberd", "Ceremonial Knife", "Blight Sickle", "Goldvein Pick", "Jousting Lance", "Hoversail Glove", "Bespoke Gauntlet", "Pirate Hat in Hand", "Adventuring Gear", "Spidersilk Glove", "Spidersilk Helm", "Strider Reins", "Truth Butcher's Armor", "Steel Armor", "Mithril Armor", "Cloak of the Wastes", "Meadow Outfit", "Isle Shirt", "Mire Leather", "Smoldering Cloak", "Karst Shawl", "Phelddagrif Plate", "Seraphim Wings", "Scrapling Shoes", "Infernal Armor", "Generous Armor", "Generous Pants", "Generous Necklace", "Generous Ring", "Generous Coin", "Medal of the Outgunned", "Medal of the Outmaneuvered", "Mad Hat", "Faerie Anklet", "Colossal Dreadmace", "Helm of Battle", "Winter Boots", "Pilgrim's Cloak", "Landscape Sketchbook - 30th Anniversary Edition", "Landscape Sketchbook - Thrones of Eldraine", "Phoenix", "Piper's Charm", "Sleep Wand", "Battle Standard", "Axt", "Bronze Sword", "Leather Boots", "Dagger", "Heart-Piercer", "Sandals", "Dark Shield", "Dark Armor", "Blood Vial", "Snack", "Mad Staff", "Dark Amulet", "Pandora's Box", "Traveler's Amulet", "Amulet of Kroog", "Lightbringers Boots", "Kiora's Bident", "Chicken Egg", "Staff of Invisibility", "Captive Soul of a Saint", "Cloudseeder Shoes", "Jolly Boots", "Brimstone Boots", "Peddler's Shoes", "Jade Anklet", "Mamba Bondband", "Empyrial Greaves", "Artificial Sliverband", "Shoes of the Swarm", "Joraga Boots", "Hellkite Greaves", "Valorous Greaves", "Battle Cry Boots", "Vindicator Greaves", "Despoiler's Boots", "Sovereign Greaves", "Bloodsworn Bite", "Victual Sliverband", "Beastbreaker Boots", "Princely Greaves", "Packsong Pants", "Acidic Sliverband", "Anklet of the End", "Spectral Sliverband", "Godsire Greaves", "Draped Dragonhide", "Cryptologist's Fins", "Soul Shoes", "Barrier Breeches", "Abzan Gauntlet", "Urza's Bauble", "Tome of Triumph", "Mishra's Bauble", "Map to the World Tree", "Keys to the House", "Poisoner's Glove", "Chimeric Coils", "Temur Gauntlet", "Tome of the Builder", "Sultai Gauntlet", "Mardu Gauntlet", "Jeskai Gauntlet", "Sinister Concoction", "Tempting Apple", "Little Black Book", "Soul-Guide Gauntlet", "Tome of Binding", "Amulet of Compleation", "Team Pennant", "Leech Breeches", "Mask of Compulsion", "Breaching Stormcrown", "Mask of Narcissism", "Acorn Amulet", "Amulet of Aeons Torn", "Roiling Stormcrown", "Amulet of Telepathy", "Bonder's Helm", "Amulet of Annihilation", "Corroding Stormcrown", "Mask of Mortiphobia", "Mask of Pyromania", "Mask of Hypochondria", "Teeming Stormcrown", "Skywise Talisman", "Crown of Winter", "Encroaching Stormcrown", "Ominous Amulet", "Silverskin Gauntlet", "Sorcerer's Wand", "Spy Kit", "Shield of the Righteous", "Steelclaw Lance", "Starforged Sword", "Trusty Machete", "Enormous Energy Blade", "Angelic Armaments", "Brawler's Cestus", "Fblthp's Lost Fishing Pole", "Ramosian Greatsword", "Ring of Xathrid", "Petrified Head", "Ring of Dragon Blood", "Ring of Thune", "Ring of Valkas", "Witches' Eye", "Ring of Kalonia", "Ring of Evos Isle", "Siren Song Lyre", "Full Moon Necklace", "Flaming Armor", "Armor of Ramunap", "Blessed Armor", "Nomad Armor", "Forbidden Robes", "Cabal Armor", "Armor of Ifnir", "Centaur Armor", "Nivix Vest", "Rancher's Garb", "Armor of Ipnu", "Heavy Armor", "Liliana's Veil", "Barbarian Armor", "Glacial Armor", "Cephalid Armor", "Cloak of Prahv", "Cloak of Svogthos", "Vitu-Ghazi Cloak", "Pinecrest Cloak", "Mantle of Dusk", "Cloak of Orzhova", "Tranquil Cloak", "Novijen Cloak", "Explorer's Cloak", "Cloudcrest Cloak", "Lantern-Lit Cloak", "Sunhome Cloak", "Sage's Robes", "Skargg Cloak", "Waterveil Cloak", "Rix Maadi Cloak", "Thunder Lasso", "Medal of the Outnumbered", "Medal of the Outmatched", "Medal of the Overpowered Opponent", "Medal of the Overwhelmed Champion", "Fleshwright Chaps", "Thirsting Axe", "Chitinous Club", "Giant's Bracer", "Jewel of Blessings", "Jewel of War", "Ghoulish Jewel", "Jewel of Rage", "High Ground Helm", "Cunning Mask", "Mighty Helm", "Helm of the Fallen", "Vampiric Amulet", "War Helm", "Helm of Contemplation", "Outlaw's Hat", "Manaforce Mace", "Hengestrider Boots", "Tome of Blight", "Hill Giant Club", "Sol Ring", "Life Amulet", "Iron Boots", "Iron Armor", "Steel Sword", "Jungle Shield", "Cursed Ring", "Presence of the Hydra", "Death Ring", "Mirror Shield", "Dungeon Map", "Crown of Growth", "Mantle of Denial", "Wood Bow", "Dark Boots", "Charm", "Magic Shard", "Entrancing Lyre", "Heavy Arbalest", "Unerring Sling", "Jeweled Amulet", "Relic Amulet", "Jinxed Ring", "Nine-Ringed Bo", "Prism Ring", "Kite Shield", "Shell Wand", "Manasight Amulet", "The Underworld Cookbook", "Bronze Blessing of Speed", "Cutthroat Skirt", "Utopia Anklet", "Amulet of Agony", "Witch-Maw Anklet", "Sheldon's Shoes", "Gixian Graft", "Yore-Tiller Anklet", "Dune-Brood Anklet", "Ghoulcaller Greaves", "Spore Skirt", "Glint-Eye Anklet", "Ink-Treader Anklet", "Fblthp's Lost Bauble", "Triangle of War", "Black Lotus", "Triassic Egg", "Ooze Amulet", "Pack Leader Pants", "Girlfriend's Skirt", "Chaos Wand", "Bear Helm", "Alesha's War Skirt", "Acolyte's Anklet", "Mask of Valgavoth", "Amulet of Awakening", "Pinnacle Circlet", "Artist's Beret", "Arguel's Amulet", "Amulet of Fury", "Amulet of Mirth", "Worn Coin", "Amulet of Mischief", "Amulet of Scorn", "Scavenger's Bandana", "Caretaker's Cap", "Amulet of Favor", "Celestial Sword", "Aegis of the Meek", "Sword of the Ur-Dragon", "Shield of Kaldra", "Draconian Cylix", "Crucible Armor", "Crystalline Armor", "Oracle's Robes", "Armor of the First", "Librarian's Robes", "Sanctuary Armor", "Fblthp's Lost Shirt", "Armor of Urami", "Parun's Armor", "Urza's Robe", "Diamond Belt", "Generous Ingot", "Ghostfire Blade", "Dire Flail", "Poet's Quill", "Skyclave Maul", "Glorious Helm", "Helm of Myth", "Shadowspear", "Chandra's Tome", "Phoenix Charm", "Demonic Contract", "Cursed Treasure", "Farmer's Tools", "Mox Emerald", "Mox Jet", "Mox Pearl", "Mox Ruby", "Mox Sapphire", "Hivestone", "Iron Shield", "Steel Shield", "Sorin's Amulet", "Aladdin's Ring", "Spell Book", "Mithril Boots", "Mithril Shield", "Flame Sword", "Basilisk Collar", "Evil Ankh", "Concordant Boots", "Aladdin's Lamp", "Warren Tender's Baton", "Robes of Omniscience", "Gold Boots", "Gold Shield", "Gold Armor", "Change", "Treasure", "Disrupting Scepter", "The Blackstaff of Waterdeep", "Amulet of Vigor", "Veilstone Amulet", "Jandor's Ring", "Ring of Immortals", "Ring of Renewal", "Fortune Coin", "Slimefoot's Slimy Staff", "Slime-Covered Boots", "Amulet of the Deceiver", "Jace's Signature Hoodie", "Teferi's Staff", "Garruk's Mighty Axe", "Nahiri's Armory", "Giant Scythe", "Tibalt's Bag of Tricks", "Xira's Fancy Hat", "Mantle of Ancient Lore", "Zedruu's Lantern", "Grolnok's Skin", "Slobad's Iron Boots", "Hallowed Sigil", "Unhallowed Sigil", "Crown of the False God", "Kobold King's Blade", "Crown of the Vale", "Tasty Tome", "Shield of Air", "Attendant's Prayerbook", "Tome of the Trove", "Grovetender's Robes", "Rainbow Spear", "Windwalker's Blessing", "Staff of Azar", "Goblin Trumpet", "Faerie Dragon Egg", "Prismatic Egg", "Celestial Prism", "Guard's Shield", "Miller's Shoes", "Ley Line Walker Boots", "Shield of the Hivelord", "Torturer's Hood", "Staff of the Ages", "Spyglass", "Ferret Food", "Shaman's Staff", "Istvan's Axe", "Holy Symbol", "Breathstealer's Blade", "Ichor Knife", "Bog Glider Glove", "Urza's Armor", "Amulet of Gaea", "Serra's Prayer Book", "Krampus's Horns", "Santa's Hat", "Crooked Scales", "Kry Greaves", "Immortal Anklet", "Marble Mace", "Life Matrix", "Gauntlets of Chaos"
    };

    // ---- Armory Guards (2026-08-11, MOD_SCOPE.md #22) ----
    // Tiers reuse EnemyData.tier's own internal strings (Common/Uncommon/Rare/Mythic - see
    // TerritoryControl.guardFightAttackerWinChance()); display mapping is the shared
    // "Apprentice/Adept/Master/Grandmaster" convention on EnemyData.tierDisplayName() (2026-08-13
    // rename, was "Challenger" - now delegated there so guard and enemy tier labels can't drift).
    public static final String[] GUARD_TIERS_ASCENDING = {"Common", "Uncommon", "Rare", "Mythic"};

    public static String guardTierDisplayName(String tier) {
        return forge.adventure.data.EnemyData.tierDisplayName(tier);
    }

    // Weekly salary, also paid upfront on hire (user spec exact numbers, 2026-08-11). Both scaled
    // by difficultyPriceMultiplier() (round 4) - a single point of scaling covers the upfront hire
    // payment and every later weekly deduction, since both read this same function.
    public static int guardWeeklyGoldCost(String tier) {
        if (tier == null)
            return scaledCost(50);
        switch (tier) {
            case "Uncommon": return scaledCost(100);
            case "Rare": return scaledCost(150);
            case "Mythic": return scaledCost(200);
            default: return scaledCost(50);
        }
    }

    public static int guardWeeklyShardCost(String tier) {
        return "Mythic".equals(tier) ? scaledCost(5) : 0;
    }

    // 1 guard per ordinary town, 2 for the Capitol (user spec).
    public static int maxGuardsForTown(String poiName) {
        return forge.adventure.util.TownRestoration.CAPITOL_POI_NAME.equals(poiName) ? 2 : 1;
    }

    /** "Manage Guards" dialog, shown from the Armory's RewardScene page once Level 2 (user spec
     *  2026-08-11). Built fresh each time (same convention RewardScene's own "Destroy Building" /
     *  createGenericDialog already uses) rather than a persistent MapStage-style dialog singleton -
     *  RewardScene has no equivalent to stage.getDialog(), so "refresh after hiring/dismissing" is
     *  just close-then-reopen a freshly-built one. */
    public static void openManageGuardsDialog(UIScene scene, PointOfInterestChanges changes, String poiName, int objectId) {
        scene.showDialog(buildManageGuardsDialog(scene, changes, poiName, objectId));
    }

    private static Dialog buildManageGuardsDialog(UIScene scene, PointOfInterestChanges changes, String poiName, int objectId) {
        Dialog dialog = new Dialog("Guards", Controls.getSkin());
        int maxGuards = maxGuardsForTown(poiName);
        int currentCount = changes.getGuardCount();

        addContentRow(dialog, "Guards: " + currentCount + "/" + maxGuards);
        for (int i = 0; i < currentCount; i++)
            addContentRow(dialog, "- " + guardTierDisplayName(changes.getGuardTier(i)));
        addContentRow(dialog, "Your gold: " + AdventurePlayer.current().getGold() + " [+Gold]   Your shards: " + AdventurePlayer.current().getShards() + " [+Shards]");

        // Half-size buttons, 2 per row (user request 2026-08-11 - the dialog was too tall at one
        // full-width button per tier/guard). addHalfButton() below tracks column parity itself so
        // callers just add buttons in sequence, same as addButtonRow() elsewhere in this file.
        int[] column = {0};
        for (String tier : GUARD_TIERS_ASCENDING) {
            int goldCost = guardWeeklyGoldCost(tier);
            int shardCost = guardWeeklyShardCost(tier);
            // Resource icons after each amount (2026-08-11, round 4 - "follow the Exchange
            // menu's pattern"), via the same [+Gold]/[+Shards] font markup this mod's Bank dialog
            // and stock scenes (InventoryScene, ShardTraderScene) already use for these two
            // specific resources - simpler than Exchange's Image-actor approach, which exists only
            // because Wood/Stone have no font-registered icon (irrelevant here, guards are
            // gold/shards only).
            // "/wk" not "/week", and a [%75] scale prefix (2026-08-11, round 5 bug fix - user
            // report: "the armory text is too big for the buttons now") - the icon markup made an
            // already-marginal fit (this text was overflowing even before icons, at plain "50
            // gold/week") worse. See addHalfButton()'s own widened cell for the other half of this
            // fix.
            String costText = goldCost + " [+Gold]" + (shardCost > 0 ? " + " + shardCost + " [+Shards]" : "") + "/wk";
            boolean canAfford = AdventurePlayer.current().getGold() >= goldCost && AdventurePlayer.current().getShards() >= shardCost;
            boolean hasRoom = currentCount < maxGuards;
            addHalfButton(dialog, column, "[%75]Hire " + guardTierDisplayName(tier) + " (" + costText + ")", hasRoom && canAfford, () -> {
                AdventurePlayer.current().takeGold(goldCost);
                if (shardCost > 0)
                    AdventurePlayer.current().takeShards(shardCost);
                changes.hireGuard(tier, WorldSave.getCurrentSave().getWorld().getCurrentDay());
                scene.removeDialog();
                openManageGuardsDialog(scene, changes, poiName, objectId);
            });
        }
        finishHalfButtonRow(dialog, column);
        for (int i = 0; i < currentCount; i++) {
            int guardIndex = i;
            addHalfButton(dialog, column, "Dismiss " + guardTierDisplayName(changes.getGuardTier(i)), true, () -> {
                changes.removeGuardAt(guardIndex);
                scene.removeDialog();
                openManageGuardsDialog(scene, changes, poiName, objectId);
            });
        }
        finishHalfButtonRow(dialog, column);
        // Info/Close shrunk and placed side-by-side (user request 2026-08-14 - both were full-
        // width 240f buttons stacked on their own rows, taking up more vertical space than the
        // dialog needed). Same addHalfButton()/finishHalfButtonRow() pattern the Hire/Dismiss rows
        // above already use - `column` is already back at 0 from the finishHalfButtonRow() call
        // just above, so this starts a fresh half-width row.
        addHalfButton(dialog, column, "Info", true, EconomyBuildings::showGuardInfo);
        addHalfButton(dialog, column, "Close", true, scene::removeDialog);
        finishHalfButtonRow(dialog, column);
        dialog.setKeepWithinStage(true);
        return dialog;
    }

    /** "Info" button on the Manage Guards dialog (2026-08-14 user request: "a brief explanation
     *  of the whole mechanic"). Redesigned same day onto InfoTextScene (see its own class comment)
     *  after the original Dialog-based ScrollPane attempt turned out to be fundamentally broken,
     *  not just under-tuned - a Dialog's pack()-driven outer size can't be capped from an inner
     *  ScrollPane cell. Percentages below are literal, not read live from TerritoryControl's
     *  private GUARD_FIGHT_ATTACKER_BONUS/OUTLOOK_DEFENSE_BONUS/ATTACKER_SACKS_TOWN_CHANCE -
     *  same pattern the pre-existing Outlook info dialog already uses for the same reason
     *  (those constants aren't public) - keep these three numbers in sync if those ever change. */
    private static void showGuardInfo() {
        StringBuilder tiers = new StringBuilder("Tiers, weakest to strongest: ");
        for (int i = 0; i < GUARD_TIERS_ASCENDING.length; i++) {
            String tier = GUARD_TIERS_ASCENDING[i];
            if (i > 0)
                tiers.append(", ");
            tiers.append(guardTierDisplayName(tier)).append(" (").append(guardWeeklyGoldCost(tier)).append("g/wk");
            int shardCost = guardWeeklyShardCost(tier);
            if (shardCost > 0)
                tiers.append(" + ").append(shardCost).append(" shards/wk");
            tiers.append(")");
        }
        java.util.List<String> paragraphs = java.util.Arrays.asList(
                "A town holds 1 guard, the Capitol holds 2. Hired guards are paid weekly "
                        + "(upfront on hire too) - miss a payment and that guard disbands.",
                tiers.toString(),
                "When a mage attacks, it must defeat every hired guard, strongest first, before "
                        + "it can even attempt to take the town - lose a guard fight and that "
                        + "guard is gone for good, but the mage moves on to the next one (or to "
                        + "the town itself if none remain). Beat every guard yourself and the "
                        + "attack ends there - it never reaches the town.",
                "Each fight's odds come from the two tiers facing off (a higher tier is a "
                        + "stronger fighter), plus the attacker always gets +10%, minus 5% if "
                        + "this town has an Outlook built. Same-tier vs. same-tier is close to "
                        + "50/50 before that adjustment; a bigger tier gap swings it hard either way.",
                "If every guard falls (or none were hired), the mage rolls to take the town "
                        + "itself - odds by the mage's own tier: Apprentice 10%, Adept 30%, "
                        + "Master 70%, Grandmaster 90% (also -5% with an Outlook). Winning that "
                        + "roll still has a further 20% chance to sack the town instead of "
                        + "properly capturing it - sacked, it reverts to a neutral ruin rather "
                        + "than changing hands.",
                "The Capitol works the same way through its own 2 guards and this same "
                        + "town-capture roll - but if a mage clears both guards AND wins that "
                        + "roll, it triggers a forced duel for the Capitol itself instead of the "
                        + "ordinary capture. Losing that duel ends the run.");
        forge.adventure.scene.InfoTextScene.show("How Guards Work", paragraphs);
    }

    // Teleporter art (2026-08-10, user spec): reuses the stock "portal4" (blue) animated portal
    // already shipped for the game's own dungeon entrances (sprites/portal4.atlas / the shared
    // portals.png sheet), rather than a hand-picked static icon like every other building type -
    // a real 4-frame shimmer read the exact same way WorldStage.getGoldSparkleAnimation() reuses
    // the stock Gold pickup's animation. "Closed" (a plain empty archway, region shared by every
    // portalN.atlas) shows until a SECOND teleporter exists anywhere - before that the network
    // has no usable destination, so nothing to signal as active.
    private static final String PORTAL_ATLAS = "sprites/portal4.atlas";
    private static com.badlogic.gdx.graphics.g2d.Animation<TextureRegion> teleporterActiveAnimation;

    public static TextureRegion getTeleporterClosedSprite() {
        return Config.instance().getAtlasSprite(PORTAL_ATLAS, "Closed");
    }

    public static com.badlogic.gdx.graphics.g2d.Animation<TextureRegion> getTeleporterActiveAnimation() {
        if (teleporterActiveAnimation == null) {
            com.badlogic.gdx.utils.Array<com.badlogic.gdx.graphics.g2d.TextureAtlas.AtlasRegion> frames =
                    Config.instance().getAtlas(PORTAL_ATLAS).findRegions("Active");
            teleporterActiveAnimation = new com.badlogic.gdx.graphics.g2d.Animation<>(0.15f, frames,
                    com.badlogic.gdx.graphics.g2d.Animation.PlayMode.LOOP);
        }
        return teleporterActiveAnimation;
    }

    /** True once a SECOND Teleporter exists anywhere (Capitol + towns combined) - before that
     *  a lone Teleporter has no destination, so it stays visually "Closed". */
    public static boolean isTeleporterNetworkActive() {
        return (capitolHasTeleporter() ? 1 : 0) + countTownTeleporters() >= 2;
    }

    // The waste-town map template no longer has any baked-in building art at all (see
    // MOD_CHANGELOG.md) - a rebuilt shop needs *some* icon regardless of what it became, not just
    // the 6 economy building types. "Special" shops - the various *BoosterPackShop entries, plus
    // the Equipment/Items family (*Equipment, *Items in shops.json, confirmed via their own
    // ShopData.rewards - 100% `"type":"item"`, 0% cards) - aren't normal card-selling shops, so
    // they get their own icon and skip the economy-building conversion choice entirely (see
    // buildSimpleRepairDialog()) rather than offering to convert them into a Bank/Mine/etc.
    public static boolean isBoosterShop(ShopData data) {
        return data != null && data.name != null && data.name.contains("Booster");
    }

    /**
     * The ONE armory-family name matcher - every caller (button gating, Capitol migration,
     * reserved-slot lookup) must go through this, never inline the patterns. This rule has now
     * drifted twice: 2026-08-11 the "Armory<Rarity>" names silently stopped matching the
     * suffix-only check, and 2026-08-12 the Level-2 variants ("EquipmentL2", via MapStage's
     * shopList + "L2" redirect) slipped past ALL the patterns - so an upgraded town Armory
     * migrated onto an ordinary Capitol slot as a plain shop, re-creating the exact duplicate-
     * Armory bug the migration special-case had just fixed for Level 1.
     */
    public static boolean isArmoryShopName(String name) {
        if (name == null)
            return false;
        if (name.endsWith("L2"))
            name = name.substring(0, name.length() - 2);
        return name.endsWith("Equipment") || name.endsWith("Items") || name.startsWith("Armory");
    }

    public static boolean isArmoryShop(ShopData data) {
        return data != null && isArmoryShopName(data.name);
    }

    /**
     * Guaranteed-in-stock Torch, redesigned 2026-08-14 (user report: a Torch appeared directly in
     * their inventory unbought - the prior design granted it straight to inventory on first Armory
     * visit, per the user's own clarification that was never the intent; the guarantee was always
     * meant to mean "purchasable from the shop", not "free"). A FIRST attempt at exactly that (see
     * MOD_CHANGELOG.md, 2026-08-13) hit a real, adversarial-review-confirmed bug: forcing the Torch
     * into stock only at generation time left it exposed to 5 separate regeneration paths (weekly
     * auto-reseed, re-roll shop type, re-roll inventory, Level 2 upgrade, initial load) that could
     * silently wipe it out before the player ever bought it. This version closes that gap by being
     * a PERSISTENT injection: called from every one of those same 5 sites, it keeps re-adding a
     * Torch to freshly-generated stock every single time, for as long as the guarantee remains
     * unfulfilled - not a one-shot forced generation-time slot. The `characterFlags` key
     * "firstArmoryTorchGranted" is reused with INVERTED semantics from the old design: it now means
     * "the guarantee has been fulfilled" (set when the player actually buys a Torch - see
     * RewardScene.java's BuyButton), not "already granted on open".
     */
    public static void injectGuaranteedTorchIfOwed(Array<Reward> ret, ShopData data, PointOfInterestChanges changes) {
        if (!isArmoryShop(data) || !TownRestoration.isCurrentTownPlayerOwned(changes))
            return;
        if (AdventurePlayer.current().checkCharacterFlag("firstArmoryTorchGranted"))
            return;
        ItemData torch = ItemListData.getItem("Torch");
        if (torch == null) {
            System.err.println("[TFR-FirstArmoryTorch] \"Torch\" item not found in catalog - nothing injected");
            return;
        }
        ret.add(new Reward(torch));
        System.out.println("[TFR-FirstArmoryTorch] guaranteed Torch injected into stock (shop=" + data.name + ")");
    }

    public static boolean isSpecialShop(ShopData data) {
        return isBoosterShop(data) || isArmoryShop(data);
    }

    public static TextureRegion getPlainShopSprite() {
        return Config.instance().getAtlasSprite(ATLAS, "PlainShop");
    }

    public static TextureRegion getSpecialShopSprite() {
        return Config.instance().getAtlasSprite(ATLAS, "SpecialShop");
    }

    /** Armory icon, level-aware (Task #8/#13, 2026-08-11) - real art for both levels now lives in
     *  NEW_BUILDINGS_ATLAS alongside Arena/Outlook/Spellsmith; the old economy_buildings.atlas
     *  "Armory" region this replaced is no longer referenced. */
    public static TextureRegion getArmoryShopSprite(int level) {
        return Config.instance().getAtlasSprite(NEW_BUILDINGS_ATLAS, level >= 2 ? "ArmoryLevel2" : "ArmoryLevel1");
    }

    public static boolean isProducingType(int type) {
        return type == SHARD_MINE || type == GOLD_MINE || type == LUMBER_MILL || type == STONE_MINE;
    }

    /** The economy building type registered for this specific shop, or NONE if this shop isn't one. */
    public static int getBuildingType(PointOfInterestChanges changes, int objectId) {
        if (changes == null)
            return NONE;
        int type = changes.getEconomyBuildingType(objectId);
        return type < 0 ? NONE : type;
    }

    private static String resourceProducedName(int type) {
        switch (type) {
            case SHARD_MINE: return "Shards";
            case GOLD_MINE: return "Gold";
            case LUMBER_MILL: return "Wood"; // canonical resource word per user decision 2026-08-08 (building name stays "Lumber Mill")
            case STONE_MINE: return "Stone";
            default: return "";
        }
    }

    /** Weekly payout amount for a mine type, sourced from TuningData (2026-08-16, user spec:
     *  "Gold: 50g/week. Wood: 25/w Stone 25/w Shards 20/w") - not difficulty-scaled, same as the
     *  flat RESOURCE_PRODUCTION_PER_DAY it replaces never was either. */
    private static int mineWeeklyAmount(int type) {
        TuningData tuning = Config.instance().getTuningData();
        switch (type) {
            case SHARD_MINE: return tuning.mineWeeklyShardPayout;
            case GOLD_MINE: return tuning.mineWeeklyGoldPayout;
            case LUMBER_MILL: return tuning.mineWeeklyWoodPayout;
            case STONE_MINE: return tuning.mineWeeklyStonePayout;
            default: return 0;
        }
    }

    public static void openProductionInfoDialog(MapStage stage, int type, int objectId) {
        refreshProductionInfoDialog(stage, type, objectId);
        stage.showDialog();
    }

    private static void refreshProductionInfoDialog(MapStage stage, int type, int objectId) {
        Dialog dialog = stage.getDialog();
        dialog.getContentTable().clear();
        dialog.getButtonTable().clear();
        dialog.clearListeners();
        TypingLabel nameLabel = Controls.newTypingLabel(buildingName(type));
        nameLabel.setWrap(true);
        nameLabel.skipToTheEnd();
        dialog.getContentTable().add(nameLabel).width(250f).row();

        // Weekly payout (2026-08-16, user spec), with real icons - "[+Name]" inline markup only
        // resolves a picture for Gold/Shards; Wood/Stone's markup is recognized but silently
        // fails to render one (see ResourceDisplayActor's own class comment), so those two get
        // real Image actors instead, the same proven pattern refreshExchangeDialog() already uses.
        int amount = mineWeeklyAmount(type);
        Table productionRow = new Table();
        if (type == GOLD_MINE || type == SHARD_MINE) {
            String tag = type == GOLD_MINE ? "[+Gold]" : "[+Shards]";
            TypingLabel amountLabel = Controls.newTypingLabel("Produces " + amount + " " + tag + " per week.");
            amountLabel.setWrap(true);
            amountLabel.skipToTheEnd();
            productionRow.add(amountLabel).width(250f);
        } else {
            TypingLabel prefix = Controls.newTypingLabel("Produces " + amount + " ");
            prefix.skipToTheEnd();
            productionRow.add(prefix);
            Sprite icon = Config.instance().getAtlasSprite(RESOURCE_ICON_ATLAS, type == LUMBER_MILL ? "Lumber" : "Stone");
            if (icon != null)
                productionRow.add(new Image(new TextureRegionDrawable(icon))).size(14f).padRight(2f);
            TypingLabel suffix = Controls.newTypingLabel(resourceProducedName(type) + " per week.");
            suffix.skipToTheEnd();
            productionRow.add(suffix);
        }
        dialog.getContentTable().add(productionRow).width(250f).row();
        addButtonRow(dialog, "Destroy Building", true, () ->
                openDestroyConfirmDialog(stage, objectId, () -> refreshProductionInfoDialog(stage, type, objectId)));
        dialog.getButtonTable().add(Controls.newTextButton("Close", stage::hideDialog)).width(240f).row();
        dialog.setKeepWithinStage(true);
    }

    /**
     * Outlook (2026-08-09): passive vision building - doubles a town's fog-of-war reveal radius
     * (World.rebuildPlayerTownVision(), vision only - the town's actual owned/claimable territory
     * radius is untouched, per user spec). No further interaction beyond info + destroy.
     */
    public static void openOutlookInfoDialog(MapStage stage, int objectId) {
        refreshOutlookInfoDialog(stage, objectId);
        stage.showDialog();
    }

    private static void refreshOutlookInfoDialog(MapStage stage, int objectId) {
        Dialog dialog = stage.getDialog();
        dialog.getContentTable().clear();
        dialog.getButtonTable().clear();
        dialog.clearListeners();
        // Explanatory text (user request 2026-08-11: "have some text that tells the player what
        // it does") - the multiplier line is worded dynamically (x2 town / x3 Capitol) rather
        // than hardcoded, matching getTownVisionRadiusTiles()'s own actual behavior; the defense
        // line names the real OUTLOOK_DEFENSE_BONUS value (TerritoryControl.java) rather than a
        // rounded guess.
        boolean isCapitol = TownRestoration.isCurrentTownCapitol();
        TypingLabel label = Controls.newTypingLabel("Outlook\nExpands this " + (isCapitol ? "Capitol" : "town")
                + "'s fog-of-war vision radius (x" + (isCapitol ? "3" : "2")
                + ") and reduces an attacking mage's chance to capture it by 5%.");
        label.setWrap(true);
        label.skipToTheEnd();
        dialog.getContentTable().add(label).width(250f).row();
        addButtonRow(dialog, "Destroy Building", true, () ->
                openDestroyConfirmDialog(stage, objectId, () -> refreshOutlookInfoDialog(stage, objectId)));
        dialog.getButtonTable().add(Controls.newTextButton("Close", stage::hideDialog)).width(240f).row();
        dialog.setKeepWithinStage(true);
    }

    /**
     * Teleporter (2026-08-09): fast travel between the Capitol and any town that's also built one
     * (max 5 total - see capitolHasTeleporter()/countTownTeleporters(), 4 in towns + 1 Capitol).
     * From a town, the only destination is the Capitol; from the Capitol, every town with a
     * Teleporter is offered. Travel moves the player's overworld position near the destination
     * (NOT straight inside it - user's explicit choice, "walk in through the entrance normally") -
     * same CoverScreen-fade mechanism GameStage.resetPlayerLocation() and the debug "teleport to
     * poi" command already use, just without their loadPOI() call.
     */
    public static void openTeleporterDialog(MapStage stage, int objectId) {
        refreshTeleporterDialog(stage, objectId);
        stage.showDialog();
    }

    private static void refreshTeleporterDialog(MapStage stage, int objectId) {
        Dialog dialog = stage.getDialog();
        dialog.getContentTable().clear();
        dialog.getButtonTable().clear();
        dialog.clearListeners();
        TypingLabel label = Controls.newTypingLabel("Teleporter\nWhere would you like to travel?");
        label.setWrap(true);
        label.skipToTheEnd();
        dialog.getContentTable().add(label).width(250f).row();

        List<PointOfInterest> destinations = teleporterDestinations();
        if (destinations.isEmpty()) {
            addContentRow(dialog, "No linked Teleporters yet - build one elsewhere first.");
        }
        for (PointOfInterest destination : destinations) {
            addButtonRow(dialog, "Travel to " + destination.getDisplayName(), true,
                    () -> travelTo(stage, destination));
        }
        // The Capitol's own Teleporter is the network hub - destroying it would strand every town
        // teleporter (their only destination), so only TOWN teleporters offer Destroy (user spec
        // 2026-08-09).
        if (!TownRestoration.isCurrentTownCapitol())
            addButtonRow(dialog, "Destroy Building", true, () ->
                    openDestroyConfirmDialog(stage, objectId, () -> refreshTeleporterDialog(stage, objectId)));
        dialog.getButtonTable().add(Controls.newTextButton("Close", stage::hideDialog)).width(240f).row();
        dialog.setKeepWithinStage(true);
    }

    // From the Capitol: every OTHER town that has built a Teleporter. From a town: the Capitol
    // only (a town Teleporter can't even be built until the Capitol has one - see
    // capitolHasTeleporter() - so this is never empty from a town in practice).
    private static List<PointOfInterest> teleporterDestinations() {
        List<PointOfInterest> destinations = new ArrayList<>();
        boolean inCapitol = TownRestoration.isCurrentTownCapitol();
        for (PointOfInterest poi : WorldSave.getCurrentSave().getWorld().getAllPointOfInterest()) {
            boolean isCapitol = TownRestoration.CAPITOL_POI_NAME.equals(poi.getData().name);
            if (inCapitol == isCapitol)
                continue; // skip the Capitol from the Capitol's own list, skip every non-Capitol from a town's list
            PointOfInterestChanges changes = WorldSave.getCurrentSave().peekPointOfInterestChanges(poi.getID());
            if (changes != null && changes.hasEconomyBuildingOfType(TELEPORTER))
                destinations.add(poi);
        }
        return destinations;
    }

    private static void travelTo(MapStage stage, PointOfInterest destination) {
        stage.hideDialog();
        stage.exitDungeon(false, false);
        Forge.advFreezePlayerControls = true;
        FThreads.invokeInEdtNowOrLater(() -> Forge.setTransitionScreen(new CoverScreen(() -> {
            Forge.advFreezePlayerControls = false;
            WorldStage.getInstance().setPosition(new Vector2(destination.getPosition().x - 16f, destination.getPosition().y + 16f));
            Forge.clearTransitionScreen();
        }, Forge.takeScreenshot())));
    }

    /** Is the Capitol's own Teleporter built? Towns can't offer the option until this is true. */
    public static boolean capitolHasTeleporter() {
        for (PointOfInterest poi : WorldSave.getCurrentSave().getWorld().getAllPointOfInterest()) {
            if (TownRestoration.CAPITOL_POI_NAME.equals(poi.getData().name)) {
                PointOfInterestChanges changes = WorldSave.getCurrentSave().peekPointOfInterestChanges(poi.getID());
                return changes != null && changes.hasEconomyBuildingOfType(TELEPORTER);
            }
        }
        return false;
    }

    /** How many ordinary (non-Capitol) towns currently have a Teleporter - capped at 4. */
    public static int countTownTeleporters() {
        int count = 0;
        for (PointOfInterest poi : WorldSave.getCurrentSave().getWorld().getAllPointOfInterest()) {
            if (TownRestoration.CAPITOL_POI_NAME.equals(poi.getData().name))
                continue;
            PointOfInterestChanges changes = WorldSave.getCurrentSave().peekPointOfInterestChanges(poi.getID());
            if (changes != null && changes.hasEconomyBuildingOfType(TELEPORTER))
                count++;
        }
        return count;
    }

    private static final int MAX_TOWN_TELEPORTERS = 4;

    /** Should a regular (non-Capitol) town's build menu offer the Teleporter option right now? */
    public static boolean townTeleporterAvailable() {
        return capitolHasTeleporter() && countTownTeleporters() < MAX_TOWN_TELEPORTERS;
    }

    /**
     * Undoes a rebuilt/converted shop back to rubble - no gold refunded. Clears the economy-
     * building type registration (map flag + type->objectId entry) if it had one, freeing that
     * type up to be built again (here or elsewhere in the same town). Outlook needs extra steps:
     * the vision cache keys off which towns currently have one, and the shrunken area's fog +
     * ground tiles need re-deriving or the widened vision would linger visually.
     */
    private static void destroyBuilding(MapStage stage, int objectId) {
        PointOfInterestChanges changes = stage.getChanges();
        if (changes == null)
            return;
        int type = getBuildingType(changes, objectId);
        int outlookRadiusBefore = type == OUTLOOK ? currentTownVisionRadius() : 0;
        if (type != NONE) {
            changes.getEconomyBuildingObjectIds().values().removeIf(v -> v == objectId);
            changes.getMapFlags().remove(builtFlag(type));
        }
        changes.getMapFlags().remove("shopRebuilt_" + objectId);
        if (type == OUTLOOK)
            onOutlookChanged(outlookRadiusBefore);
    }

    private static int currentTownVisionRadius() {
        PointOfInterest point = forge.adventure.scene.TileMapScene.instance().rootPoint;
        if (point == null)
            return 0;
        forge.adventure.world.World world = WorldSave.getCurrentSave().getWorld();
        return world.getTownVisionRadiusTiles(point,
                WorldSave.getCurrentSave().peekPointOfInterestChanges(point.getID()));
    }

    /**
     * Makes an Outlook build/destroy actually SHOW (user report 2026-08-09: "built an Outlook and
     * it did not extend the visible FoW" - the vision cache was rebuilt but nothing re-derived
     * the already-baked fog overlay/ground textures, so the widened tier was invisible until some
     * unrelated repaint). Refreshes over the LARGER of the before/after radius so both directions
     * work: a build brightens the new ring, a destroy re-hazes the lost one. revealArea() marks
     * any not-yet-explored ring tiles explored (a fresh Outlook genuinely uncovers ground);
     * refreshFogInRadius() re-tiers everything else.
     */
    private static void onOutlookChanged(int radiusBefore) {
        forge.adventure.world.World world = WorldSave.getCurrentSave().getWorld();
        world.rebuildPlayerTownVision();
        PointOfInterest point = forge.adventure.scene.TileMapScene.instance().rootPoint;
        if (point == null)
            return;
        int radiusAfter = currentTownVisionRadius();
        int radius = Math.max(radiusBefore, radiusAfter) + 2;
        int centerX = (int) (point.getPosition().x / world.getTileSize());
        int centerY = (int) (point.getPosition().y / world.getTileSize());
        world.revealArea(centerX, centerY, radiusAfter, WorldStage.getInstance()::refreshBackgroundTile);
        world.refreshFogInRadius(centerX, centerY, radius, WorldStage.getInstance()::refreshBackgroundTile);
    }

    /**
     * Confirmation gate for destroyBuilding() - "You will not get any resources back" per user
     * spec. onCancel re-renders whatever dialog was showing before (the calling building's own
     * info/interaction view); onDestroyed callers all just want the dialog closed outright, since
     * there's nothing left to show once the shop reverts to rubble.
     */
    public static void openDestroyConfirmDialog(MapStage stage, int objectId, Runnable onCancel) {
        Dialog dialog = stage.getDialog();
        dialog.getContentTable().clear();
        dialog.getButtonTable().clear();
        dialog.clearListeners();
        addContentRow(dialog, "Destroy this building?\nYou will not get any resources back.");
        addButtonRow(dialog, "Destroy", true, () -> {
            destroyBuilding(stage, objectId);
            stage.hideDialog();
        });
        addButtonRow(dialog, "Cancel", true, onCancel);
        dialog.setKeepWithinStage(true);
    }

    /**
     * Destroys a plain Card Shop / Booster shop from INSIDE its RewardScene page (user revision
     * 2026-08-09: the first version inserted an Enter/Destroy/Leave gate before the shop, an
     * extra click on every visit - replaced by a Destroy Building button on the shop page
     * itself). Called by RewardScene after its own confirmation dialog; the shop's MapStage is
     * still the live one underneath the scene. Returns to the map with the shop as rubble.
     */
    public static void destroyShopFromRewardScene(ShopActor actor) {
        destroyBuilding(actor.getMapStage(), actor.getObjectId());
        // Re-roll the shop's TYPE right at destruction (2026-08-15 user report: "destroy a Shop,
        // then re-build... it's the same type shop that gets rebuilt. I would have thought that
        // it would be random"). Clearing state alone would NOT achieve that: the load-time type
        // roll is deliberately deterministic per POI (the world RNG is reseeded from the POI's
        // own seedOffset before every map load), so without an explicit new pin the same type
        // always comes back. rerollShopType() is the existing machinery - picks a different
        // random type from this object's own candidate pool, pins it (so it survives re-entry),
        // and swaps the sign sprite (which stays hidden while the shop is rubble, so no spoiler).
        // Null return = this object had no multi-name candidate pool to roll from - keep the old
        // identity, same graceful fallback the Re-roll Shop Type button itself has.
        ShopData newData = actor.getMapStage().rerollShopType(actor.getObjectId(), actor.getShopData().name);
        if (newData != null) {
            actor.setShopData(newData);
            // Fresh inventory seed too (also clears cardsBought) - the new identity shouldn't
            // inherit the old shop's purchase history or stock roll.
            actor.getMapStage().getChanges().generateNewShopSeed(actor.getObjectId());
            System.out.println("[TFR-ShopRebuild] destroyed shop " + actor.getObjectId()
                    + " will rebuild as '" + newData.name + "'");
        }
    }

    // ---------------------------------------------------------------- multi-resource costs
    // 2026-08-12 cost overhaul (user's table): building costs now mix Gold/Wood/Stone/Shards.
    // Every component is difficulty-scaled through the same scaledCost() gold always used, and
    // the label/affordability/deduction triple always derives from ONE set of scaled values so
    // they can't disagree. Labels use the [+Gold]/[+Wood]/[+Stone]/[+Shards] font glyphs
    // (Wood/Stone regions added to the plane's own sprites/items.atlas - the same atlas
    // Controls.getTextraFont() registers, which is why these two tags work where the old
    // second-atlas attempt in ResourceDisplayActor's comment did not).

    /** "250 [+Gold] + 150 [+Stone]" from BASE values (each component difficulty-scaled here);
     *  zero components are skipped. */
    public static String costLabel(int gold, int wood, int stone, int shards) {
        StringBuilder sb = new StringBuilder();
        appendCostPart(sb, gold, "[+Gold]");
        appendCostPart(sb, wood, "[+Wood]");
        appendCostPart(sb, stone, "[+Stone]");
        appendCostPart(sb, shards, "[+Shards]");
        return sb.length() == 0 ? "free" : sb.toString();
    }

    private static void appendCostPart(StringBuilder sb, int baseAmount, String icon) {
        if (baseAmount <= 0)
            return;
        if (sb.length() > 0)
            sb.append(" + ");
        sb.append(scaledCost(baseAmount)).append(' ').append(icon);
    }

    public static boolean canAffordCost(int gold, int wood, int stone, int shards) {
        AdventurePlayer player = AdventurePlayer.current();
        return player.getGold() >= scaledCost(gold)
                && player.getWood() >= scaledCost(wood)
                && player.getStone() >= scaledCost(stone)
                && player.getShards() >= scaledCost(shards);
    }

    /** Immediate payment for TextraButton flows (upgrades, research). Callers gate on
     *  canAffordCost() first - this does not re-check. */
    public static void payCost(int gold, int wood, int stone, int shards) {
        AdventurePlayer player = AdventurePlayer.current();
        if (gold > 0) player.takeGold(scaledCost(gold));
        if (wood > 0) player.takeWood(scaledCost(wood));
        if (stone > 0) player.takeStone(scaledCost(stone));
        if (shards > 0) player.takeShards(scaledCost(shards));
    }

    /** One ActionData deducting every non-zero (scaled) component - MapDialog handles all four
     *  fields (addWood/addStone are mod additions to DialogData.ActionData). */
    public static DialogData.ActionData spendCostAction(int gold, int wood, int stone, int shards) {
        DialogData.ActionData action = new DialogData.ActionData();
        action.addGold = -scaledCost(gold);
        action.addShards = -scaledCost(shards);
        action.addWood = -scaledCost(wood);
        action.addStone = -scaledCost(stone);
        return action;
    }

    private static DialogData.ActionData spendGoldAction(int cost) {
        DialogData.ActionData action = new DialogData.ActionData();
        action.addGold = -cost;
        return action;
    }

    private static DialogData.ActionData setShopRebuiltAction(int objectId) {
        DialogData.ActionData.QuestFlag flag = new DialogData.ActionData.QuestFlag();
        flag.key = "shopRebuilt_" + objectId;
        flag.val = 1;
        DialogData.ActionData action = new DialogData.ActionData();
        action.setMapFlag = flag;
        return action;
    }

    private static DialogData.ActionData setEconomyTypeAction(int type) {
        DialogData.ActionData.QuestFlag flag = new DialogData.ActionData.QuestFlag();
        flag.key = ECONOMY_TYPE_FLAG;
        flag.val = type;
        DialogData.ActionData action = new DialogData.ActionData();
        action.setMapFlag = flag;
        return action;
    }

    // One town can have at most one of each of the 6 special types (a Bank AND a Gold Mine AND
    // an Exchange, etc. are all fine together - just not two Banks), so gating is per-type, keyed
    // "economyBuilt_<type>" - distinct from ECONOMY_TYPE_FLAG below, which is only a one-shot
    // "which option did the player just pick" signal for the dialog-complete listener to read.
    private static String builtFlag(int type) {
        return "economyBuilt_" + type;
    }

    private static DialogData.ConditionData noBuildingOfTypeYetCondition(int type) {
        DialogData.ConditionData condition = new DialogData.ConditionData();
        condition.checkMapFlag = builtFlag(type);
        condition.not = true;
        return condition;
    }

    private static DialogData.ActionData setBuiltFlagAction(int type) {
        DialogData.ActionData.QuestFlag flag = new DialogData.ActionData.QuestFlag();
        flag.key = builtFlag(type);
        flag.val = 1;
        DialogData.ActionData action = new DialogData.ActionData();
        action.setMapFlag = flag;
        return action;
    }

    /**
     * Registers an economy building on a town's changes entry as if it had been built there:
     * type->objectId mapping, the shop's rebuilt flag, AND the one-per-type economyBuilt flag.
     * Built for the Capitol migration (2026-08-08 late) - its first version only set the former
     * two, so the Capitol's build menu happily offered a second Gold Mine even though the town's
     * mine had just migrated in (the menu's exclusion reads the economyBuilt_<type> flag, nothing
     * else - user-reported).
     */
    public static void registerMigratedBuilding(PointOfInterestChanges changes, int type, int objectId) {
        changes.setEconomyBuildingObjectId(type, objectId);
        changes.getMapFlags().put("shopRebuilt_" + objectId, (byte) 1);
        changes.getMapFlags().put(builtFlag(type), (byte) 1);
        // Mine weekly-payout baseline (2026-08-16) - a migrated mine's weekly cycle restarts
        // fresh from the migration day, same as a freshly-built one would.
        if (isProducingType(type))
            changes.setEconomyBuildingLastPayoutDay(type, WorldSave.getCurrentSave().getWorld().getCurrentDay());
    }

    // Always shown (rather than hidden via a condition) so the player can see the cost even when
    // short on gold - just greyed out via isDisabled, same pattern already used by the Bank/
    // Exchange dialogs' addButtonRow(). "Already have one of this type" is still a hard hide via
    // condition though, since that's a structural exclusion, not an affordability one.
    /** Per-type BASE build costs {gold, wood, stone, shards} - 2026-08-12 user cost table.
     *  NONE = rebuilding the slot as a plain shop. Wood/Stone components halved 2026-08-21
     *  (v1.00 feedback round) - gold/shard components untouched. */
    private static int[] buildCostFor(int type) {
        switch (type) {
            case SHARD_MINE:
            case GOLD_MINE:
            case LUMBER_MILL:
            case STONE_MINE:    return new int[]{250, 0, 75, 0};
            case BANK:          return new int[]{500, 0, 0, 0};
            case EXCHANGE:      return new int[]{150, 75, 75, 0};
            case OUTLOOK:       return new int[]{0, 125, 0, 0};
            case TELEPORTER:    return new int[]{0, 0, 0, 200};
            case ARCHAEOLOGIST: return new int[]{0, 0, 175, 0};
            // 2026-08-22: gold-only by design - Trader's whole purpose is converting gold INTO
            // wood/stone, so charging wood/stone to build it would be backwards. No user-specified
            // amount given; chosen to sit below Exchange's 150g (cheaper, earlier-game) and well
            // below Bank's 500g. Revisit if it feels off in testing.
            case TRADER:        return new int[]{200, 0, 0, 0};
            default:            return new int[]{100, 5, 0, 0}; // NONE / plain shop
        }
    }

    private static DialogData buildOption(int type, int objectId) {
        DialogData option = new DialogData();
        // One base-cost tuple feeds label, affordability, and deduction (each component
        // difficulty-scaled inside the helpers) so the three can't disagree.
        int[] c = buildCostFor(type);
        String label = buildingName(type) + " (" + costLabel(c[0], c[1], c[2], c[3]) + ")";
        // The 5-total cap is otherwise invisible until it silently stops offering the option -
        // show progress the same way the Capitol upgrade button shows its town count (user spec
        // 2026-08-09).
        if (type == TELEPORTER)
            label = buildingName(type) + " (" + costLabel(c[0], c[1], c[2], c[3]) + ", "
                    + (countTownTeleporters() + (capitolHasTeleporter() ? 1 : 0)) + "/" + (MAX_TOWN_TELEPORTERS + 1) + " built)";
        option.name = label;
        option.isDisabled = !canAffordCost(c[0], c[1], c[2], c[3]);
        if (type == NONE) {
            // Edition-restriction stale-bake-in fix (2026-08-13, adversarial review) - this is the
            // plain "Card Shop" rebuild option ShopActor.onPlayerCollide() routes every ordinary
            // wasteland shop through, and the only one of the four restoration/rebuild flows that
            // was missed the first time (buildRestoreTownDialog/buildRebuildShopDialog/
            // buildSimpleRepairDialog all got it) - without this, a plain shop rebuilt here (or
            // Destroy Building + rebuilt again) kept whatever edition shard it was born with
            // indefinitely, since destroyBuilding() doesn't touch rewardData either.
            DialogData.ActionData refreshShops = new DialogData.ActionData();
            refreshShops.refreshShopRewardsTrigger = "shop-rebuild";
            option.action = new DialogData.ActionData[]{spendCostAction(c[0], c[1], c[2], c[3]), setShopRebuiltAction(objectId), refreshShops};
        } else {
            option.condition = new DialogData.ConditionData[]{noBuildingOfTypeYetCondition(type)};
            option.action = new DialogData.ActionData[]{spendCostAction(c[0], c[1], c[2], c[3]), setShopRebuiltAction(objectId), setEconomyTypeAction(type), setBuiltFlagAction(type)};
        }
        return option;
    }

    // "Is there anything left to build in this submenu" checks (2026-08-09, user request: an
    // empty submenu button is a dead click - hide it). Reads the same economyBuilt_<type> flag
    // buildOption()'s per-option hide condition uses.
    private static boolean typeAvailable(MapStage stage, int type) {
        return !stage.checkQuestFlag(builtFlag(type));
    }

    /**
     * Build-choice dialog shown the first time a wasteland shop is rebuilt: Card Shop / Industry
     * (submenu: 4 production types) / Financial (Capitol-only submenu: Bank, Exchange) / Utility
     * (submenu: Outlook, Teleporter once unlocked) / Not now. Nested into submenus (2026-08-09,
     * user request) now that the option count outgrew a single flat page - was Card Shop/Bank/
     * Exchange/Industry-submenu for the Capitol, Card Shop/4-mines-flat for towns. Reads back
     * ECONOMY_TYPE_FLAG once the dialog closes and, if the player just chose one of the special
     * buildings, imperatively records this shop under that type (economyBuildingObjectId can't
     * fit through the byte-limited map-flag system - see PointOfInterestChanges).
     */
    public static MapDialog buildChooseBuildingDialog(MapStage stage, int objectId) {
        // Reset the one-shot "which option did the player pick" flag BEFORE showing the menu.
        // It used to persist forever after a build, which was harmless while buildings were
        // permanent - but with Destroy in play a stale value could re-register the destroyed
        // type onto whatever shop's menu closes next, free of charge (found by review, 2026-08-09).
        if (stage.getChanges() != null)
            stage.getChanges().getMapFlags().put(ECONOMY_TYPE_FLAG, (byte) NONE);
        DialogData root = new DialogData();
        root.text = "This shop is buried in rubble. What would you like to rebuild it as?";

        DialogData notNow = new DialogData();
        notNow.name = "Not now";

        boolean isCapitol = TownRestoration.isCurrentTownCapitol();
        List<DialogData> rootOptions = new ArrayList<>();
        List<DialogData> backButtons = new ArrayList<>();
        rootOptions.add(buildOption(NONE, objectId));

        // Bank stays Capitol-exclusive per the earlier 2026-08-08 decision. Trader (2026-08-22)
        // is the odd one out in this submenu - deliberately buildable in ANY town, not just the
        // Capitol, so it can serve as an early-game resource source before a player has
        // reached/built their Capitol at all. Exchange is deliberately NOT offered here at all
        // (2026-08-22 user report: "you currently have the option to build a trade or exchange.
        // You can't build an Exchange without a trade first, so remove the Exchange build
        // option") - the ONLY route to an Exchange is upgrading a Capitol Trader in place (see
        // refreshTraderDialog()'s "Upgrade to Exchange" row / upgradeTraderToExchange()).
        boolean traderOffered = typeAvailable(stage, TRADER);
        boolean bankOffered = isCapitol && typeAvailable(stage, BANK);
        if (traderOffered || bankOffered) {
            DialogData financialBack = new DialogData();
            financialBack.name = "Back";
            backButtons.add(financialBack);
            DialogData financial = new DialogData();
            financial.name = "Financial";
            financial.text = "Which financial building?";
            List<DialogData> financialOptions = new ArrayList<>();
            if (bankOffered)
                financialOptions.add(buildOption(BANK, objectId));
            if (traderOffered)
                financialOptions.add(buildOption(TRADER, objectId));
            financialOptions.add(financialBack);
            financial.options = financialOptions.toArray(new DialogData[0]);
            rootOptions.add(financial);
        }

        if (typeAvailable(stage, SHARD_MINE) || typeAvailable(stage, GOLD_MINE)
                || typeAvailable(stage, LUMBER_MILL) || typeAvailable(stage, STONE_MINE)) {
            DialogData industryBack = new DialogData();
            industryBack.name = "Back";
            backButtons.add(industryBack);
            DialogData industry = new DialogData();
            industry.name = "Industry";
            industry.text = "Which industry building?";
            industry.options = new DialogData[]{
                    buildOption(SHARD_MINE, objectId),
                    buildOption(GOLD_MINE, objectId),
                    buildOption(LUMBER_MILL, objectId),
                    buildOption(STONE_MINE, objectId),
                    industryBack
            };
            rootOptions.add(industry);
        }

        // Teleporter unlock (user spec 2026-08-09): the Capitol's own build menu always offers it
        // (auto-hidden once built, same one-per-type condition every other type already uses) -
        // an ordinary town only offers it once the Capitol has built one AND fewer than 4 towns
        // already have (townTeleporterAvailable() - a cross-POI check the declarative condition
        // system below can't express, so it's gated imperatively here instead).
        boolean teleporterOffered = (isCapitol || townTeleporterAvailable()) && typeAvailable(stage, TELEPORTER);
        // Archaeologist (2026-08-11): Capitol-only, same as Financial's Bank/Exchange - see the
        // ARCHAEOLOGIST constant's own comment for why it can't be built in ordinary towns.
        boolean archaeologistOffered = isCapitol && typeAvailable(stage, ARCHAEOLOGIST);
        if (typeAvailable(stage, OUTLOOK) || teleporterOffered || archaeologistOffered) {
            DialogData utilityBack = new DialogData();
            utilityBack.name = "Back";
            backButtons.add(utilityBack);
            DialogData utility = new DialogData();
            utility.name = "Utility";
            utility.text = "Which utility building?";
            List<DialogData> utilityOptions = new ArrayList<>();
            utilityOptions.add(buildOption(OUTLOOK, objectId));
            if (teleporterOffered)
                utilityOptions.add(buildOption(TELEPORTER, objectId));
            if (archaeologistOffered)
                utilityOptions.add(buildOption(ARCHAEOLOGIST, objectId));
            utilityOptions.add(utilityBack);
            utility.options = utilityOptions.toArray(new DialogData[0]);
            rootOptions.add(utility);
        }

        rootOptions.add(notNow);
        root.options = rootOptions.toArray(new DialogData[0]);
        // "Back" just re-shows the top-level menu - same content, not a true navigation stack.
        for (DialogData back : backButtons) {
            back.text = root.text;
            back.options = root.options;
        }

        MapDialog dialog = new MapDialog(root, stage, objectId, null);
        dialog.addDialogCompleteListener(new ChangeListener() {
            @Override
            public void changed(ChangeEvent event, Actor actor) {
                PointOfInterestChanges changes = stage.getChanges();
                if (changes == null)
                    return;
                int chosenType = stage.getQuestFlag(ECONOMY_TYPE_FLAG);
                if (chosenType != NONE && !changes.hasEconomyBuildingOfType(chosenType)) {
                    changes.setEconomyBuildingObjectId(chosenType, objectId);
                    // Mine weekly-payout baseline (2026-08-16, user spec: "if you build it on day
                    // 3, it will still payout day 7 the first time") - seeding lastPayoutDay to
                    // the construction day is all processDaysPassed()'s boundary math needs to
                    // land the first payout on the next shared day-7-multiple, same as
                    // hireGuard() seeding guardLastPaidDay to the hire day.
                    if (isProducingType(chosenType))
                        changes.setEconomyBuildingLastPayoutDay(chosenType, WorldSave.getCurrentSave().getWorld().getCurrentDay());
                    // Full visual refresh, not just the cache rebuild - see onOutlookChanged()
                    // (the cache-only version was the "built an Outlook, nothing happened" bug).
                    if (chosenType == OUTLOOK)
                        onOutlookChanged(0);
                }
            }
        });
        return dialog;
    }

    /**
     * Simplified rebuild dialog for "special" shops (see isSpecialShop()) and the Capitol's fixed
     * land shops - just repairs the shop back to itself, skipping the Bank/Exchange/Industry
     * conversion choice entirely. Converting a themed booster/armory/land shop into a generic
     * economy building doesn't make sense, and it was never a normal Card Shop to begin with, so
     * buildOption(NONE, ...)'s "Card Shop" label would be wrong here too - this builds its own
     * single option instead of reusing that one. The label names WHAT is being repaired (user
     * request 2026-08-09 - "Repair Green Land Shop", "Repair Booster Shop", "Repair Armory"), so
     * the player knows it's not just another generic card shop.
     */
    public static MapDialog buildSimpleRepairDialog(MapStage stage, int objectId, ShopData data) {
        String landShop = landShopLabel(data);
        if (landShop != null) {
            String uncapitaledColor = landShopCapitalNotYetVisited(data);
            if (uncapitaledColor != null) {
                DialogData blocked = new DialogData();
                blocked.text = "This shop is buried in rubble. You'll need to visit the "
                        + uncapitaledColor + " Capital at least once before you can restore it.";
                DialogData ok = new DialogData();
                ok.name = "OK";
                blocked.options = new DialogData[]{ok};
                return new MapDialog(blocked, stage, objectId, null);
            }
        }

        DialogData root = new DialogData();
        root.text = "This shop is buried in rubble. Repair it?";

        String what;
        if (landShop != null)
            what = "Repair " + landShop;
        else if (isArmoryShop(data))
            what = "Restore Armory"; // user's exact wording, 2026-08-11 - the Capitol's reserved slot
        else if (isBoosterShop(data))
            what = "Repair Booster Shop";
        else
            what = "Repair Shop";

        DialogData repair = new DialogData();
        // Per-shop-type repair costs (2026-08-12 user cost table): Armory 250g+125 wood,
        // Booster 200g+5 stone, the 6 land shops 50g+3 wood, everything else plain-shop cost.
        // Wood/Stone components halved (rounding up) 2026-08-21 - gold untouched.
        int[] c;
        if (isArmoryShop(data))
            c = new int[]{250, 125, 0, 0};
        else if (isBoosterShop(data))
            c = new int[]{200, 0, 5, 0};
        else if (landShop != null)
            c = new int[]{50, 3, 0, 0};
        else
            c = new int[]{100, 5, 0, 0};
        repair.name = what + " (" + costLabel(c[0], c[1], c[2], c[3]) + ")";
        repair.isDisabled = !canAffordCost(c[0], c[1], c[2], c[3]);
        DialogData.ActionData refreshShops = new DialogData.ActionData();
        refreshShops.refreshShopRewardsTrigger = "shop-repair";
        repair.action = new DialogData.ActionData[]{spendCostAction(c[0], c[1], c[2], c[3]), setShopRebuiltAction(objectId), refreshShops};

        DialogData notNow = new DialogData();
        notNow.name = "Not now";

        root.options = new DialogData[]{repair, notNow};
        return new MapDialog(root, stage, objectId, null);
    }

    // The Capitol's six fixed land shops carry the plain basic-land ShopData names - map them to
    // the color the player actually thinks in ("Forest" IS the green land shop). Null for
    // anything that isn't one of the six.
    private static String landShopLabel(ShopData data) {
        if (data == null || data.name == null)
            return null;
        switch (data.name) {
            case "Plains": return "White Land Shop";
            case "Island": return "Blue Land Shop";
            case "Swamp": return "Black Land Shop";
            case "Mountain": return "Red Land Shop";
            case "Forest": return "Green Land Shop";
            case "Land": return "Utility Land Shop";
            default: return null;
        }
    }

    // Land shop visit-gate (2026-08-11 user spec): a color-specific land shop can't be repaired
    // until the player has visited that color's own AI capital at least once. "Land" (Utility -
    // colorless) has no capital and is deliberately exempt. Returns the display color name
    // ("White") if the gate is blocking, null if the shop isn't gated or the gate is satisfied.
    private static String landShopCapitalNotYetVisited(ShopData data) {
        if (data == null || data.name == null)
            return null;
        String color;
        String capitalName;
        switch (data.name) {
            case "Plains": color = "White"; capitalName = "Plains Capital"; break;
            case "Island": color = "Blue"; capitalName = "Island Capital"; break;
            case "Swamp": color = "Black"; capitalName = "Swamp Capital"; break;
            case "Mountain": color = "Red"; capitalName = "Mountain Capital"; break;
            case "Forest": color = "Green"; capitalName = "Forest Capital"; break;
            default: return null; // "Land" (Utility) or not one of the 6 land shops - never gated
        }
        PointOfInterest capital = WorldSave.getCurrentSave().getWorld().findPointsOfInterest(capitalName);
        if (capital == null)
            return null; // no capital in this world for some reason - don't block on it
        boolean visited = WorldSave.getCurrentSave().getPointOfInterestChanges(capital.getID()).isVisited();
        return visited ? null : color;
    }

    // ---- Bank / Exchange interaction dialogs (built directly, not via DialogData, since they
    // need repeatable custom Java logic - bank balance and Wood/Stone aren't expressible through
    // the declarative ActionData system used by ordinary map dialogs). ----

    private static final int BANK_DENOMINATION = 100;

    public static void openBankDialog(MapStage stage, PointOfInterestChanges changes, int objectId) {
        refreshBankDialog(stage, changes, objectId);
        stage.showDialog();
    }

    // Separate labels per line (rather than one \n-joined string) so the balance/gold lines can't
    // get lost to any single label's own width/wrap sizing - each row gets its own Table cell.
    private static void refreshBankDialog(MapStage stage, PointOfInterestChanges changes, int objectId) {
        Dialog dialog = stage.getDialog();
        dialog.getContentTable().clear();
        dialog.getButtonTable().clear();
        dialog.clearListeners();

        AdventurePlayer player = AdventurePlayer.current();
        addContentRow(dialog, "[+Gold]Bank");
        addContentRow(dialog, "Deposited: " + changes.getBankBalance() + " [+Gold]");
        addContentRow(dialog, "[%80]" + Math.round(INTEREST_RATE * 100) + "% interest every " + INTEREST_PERIOD_DAYS + " days[%]");
        addContentRow(dialog, "Your gold: " + player.getGold() + " [+Gold]");

        // Bank preferences (2026-08-13, user spec) - plain scene2d CheckBoxes, first ever used
        // inside a Dialog in this mod (existing CheckBox usage elsewhere is all full-screen
        // UIScene root tables - see Controls.newCheckBox()). State is re-read from AdventurePlayer
        // on every rebuild since refreshBankDialog() discards and recreates every Actor each call.
        CheckBox bankFirstBox = Controls.newCheckBox("Pay Guards from Bank first (Gold only)");
        bankFirstBox.setChecked(player.isPayGuardsFromBankFirst());
        bankFirstBox.addListener(new ChangeListener() {
            @Override
            public void changed(ChangeEvent event, Actor actor) {
                player.setPayGuardsFromBankFirst(((CheckBox) actor).isChecked());
            }
        });
        dialog.getContentTable().add(bankFirstBox).width(250f).row();

        CheckBox mineToBankBox = Controls.newCheckBox("Gold Mine deposits into Bank Directly");
        mineToBankBox.setChecked(player.isGoldMineDepositsToBankDirectly());
        mineToBankBox.addListener(new ChangeListener() {
            @Override
            public void changed(ChangeEvent event, Actor actor) {
                player.setGoldMineDepositsToBankDirectly(((CheckBox) actor).isChecked());
            }
        });
        dialog.getContentTable().add(mineToBankBox).width(250f).row();

        // Icons after each amount (2026-08-11, round 4 - "follow the Exchange menu's pattern"),
        // via [+Gold] font markup - not difficulty-scaled, deposits/withdrawals move the player's
        // own money rather than costing anything.
        // Half-width, packed 2 per row (2026-08-13 fix - user report: with the two checkboxes
        // above added, 6 full-width button rows pushed the dialog taller than the screen,
        // clipping the "Deposited:"/interest/header rows off the TOP - setKeepWithinStage() can
        // only reposition a dialog, not shrink one taller than the stage). Same pattern already
        // used for the Exchange dialog's Buy/Sell pairs and the Manage Guards dialog's Hire/
        // Dismiss pairs. Unlike those two, no [%] scale-down needed here - "Withdraw 100
        // [+Gold]"/"Deposit All" etc. are all shorter than "Dismiss Uncommon"/"Dismiss Mythic",
        // which already fit this same 140f width unscaled.
        int[] column = {0};
        addHalfButton(dialog, column, "Deposit " + BANK_DENOMINATION + " [+Gold]", player.getGold() >= BANK_DENOMINATION, () -> {
            player.takeGold(BANK_DENOMINATION);
            changes.addBankBalance(BANK_DENOMINATION);
            refreshBankDialog(stage, changes, objectId);
        });
        addHalfButton(dialog, column, "Deposit All", player.getGold() > 0, () -> {
            int all = player.getGold();
            player.takeGold(all);
            changes.addBankBalance(all);
            refreshBankDialog(stage, changes, objectId);
        });
        addHalfButton(dialog, column, "Withdraw " + BANK_DENOMINATION + " [+Gold]", changes.getBankBalance() >= BANK_DENOMINATION, () -> {
            changes.addBankBalance(-BANK_DENOMINATION);
            player.giveGold(BANK_DENOMINATION);
            refreshBankDialog(stage, changes, objectId);
        });
        addHalfButton(dialog, column, "Withdraw All", changes.getBankBalance() > 0, () -> {
            int all = changes.getBankBalance();
            changes.addBankBalance(-all);
            player.giveGold(all);
            refreshBankDialog(stage, changes, objectId);
        });
        finishHalfButtonRow(dialog, column);
        // Side-by-side, shrunk (2026-08-15 user request - was two stacked full-width rows,
        // visually lopsided against the half-width Deposit/Withdraw buttons above) - same
        // addHalfButton() pairing already used for those and for the Guards dialog's Info/Close.
        addHalfButton(dialog, column, "Destroy Building", true, () ->
                openDestroyConfirmDialog(stage, objectId, () -> refreshBankDialog(stage, changes, objectId)));
        addHalfButton(dialog, column, "Close", true, stage::hideDialog);
        finishHalfButtonRow(dialog, column);
        dialog.setKeepWithinStage(true);
    }

    private static void addContentRow(Dialog dialog, String text) {
        TypingLabel label = Controls.newTypingLabel(text);
        label.setWrap(true);
        label.skipToTheEnd();
        dialog.getContentTable().add(label).width(250f).row();
    }

    // (Gold->Shard, Shard->Gold, Gold->Wood, Wood->Gold, Gold->Stone, Stone->Gold). Standardized
    // (per feedback) to one denomination for every resource: buy 5 for 100 gold, sell 5 back for
    // 80 gold (80% buyback, a flat 20% spread) - previously each resource had its own bespoke
    // rate/quantity, no longer the case.
    private static final class Trade {
        final String verb; // "Buy" or "Sell"
        final String resourceAtlas, resourceIcon; // the non-Gold side of the trade
        final int giveGold, giveShards, giveWood, giveStone;
        final int getGold, getShards, getWood, getStone;
        Trade(String verb, String resourceAtlas, String resourceIcon,
              int giveGold, int giveShards, int giveWood, int giveStone,
              int getGold, int getShards, int getWood, int getStone) {
            this.verb = verb;
            this.resourceAtlas = resourceAtlas;
            this.resourceIcon = resourceIcon;
            this.giveGold = giveGold; this.giveShards = giveShards; this.giveWood = giveWood; this.giveStone = giveStone;
            this.getGold = getGold; this.getShards = getShards; this.getWood = getWood; this.getStone = getStone;
        }
        boolean affordable(AdventurePlayer player) {
            return player.getGold() >= giveGold && player.getShards() >= giveShards
                    && player.getWood() >= giveWood && player.getStone() >= giveStone;
        }
        void apply(AdventurePlayer player) {
            if (giveGold > 0) player.takeGold(giveGold);
            if (giveShards > 0) player.takeShards(giveShards);
            if (giveWood > 0) player.takeWood(giveWood);
            if (giveStone > 0) player.takeStone(giveStone);
            if (getGold > 0) player.giveGold(getGold);
            if (getShards > 0) player.addShards(getShards);
            if (getWood > 0) player.addWood(getWood);
            if (getStone > 0) player.addStone(getStone);
        }
    }

    private static final int TRADE_UNITS = 5;
    private static final int TRADE_BUY_PRICE = 100;
    private static final int TRADE_SELL_PRICE = 80;
    private static final String RESOURCE_ICON_ATLAS = "maps/tileset/resource_icons.atlas";

    // Every trade shows real icons for both sides now - Gold/Shards from the shared items.atlas
    // (same one [+Gold]/[+Shards] markup elsewhere reads from), Lumber/Stone from the small
    // dedicated resource_icons.atlas (see ResourceDisplayActor) - built as real Image actors via
    // buildTradeRow() below rather than inline font markup, since Lumber/Stone's icons were never
    // registered with the font (and, being in the mod plane's own resources, registering them
    // globally in Controls.getTextraFont() risked a null-FileHandle crash for every other plane -
    // see MOD_CHANGELOG.md).
    private static final Trade[] TRADES = {
            new Trade("Buy", Paths.ITEMS_ATLAS, "Shards", TRADE_BUY_PRICE, 0, 0, 0, 0, TRADE_UNITS, 0, 0),
            new Trade("Sell", Paths.ITEMS_ATLAS, "Shards", 0, TRADE_UNITS, 0, 0, TRADE_SELL_PRICE, 0, 0, 0),
            new Trade("Buy", RESOURCE_ICON_ATLAS, "Lumber", TRADE_BUY_PRICE, 0, 0, 0, 0, 0, TRADE_UNITS, 0),
            new Trade("Sell", RESOURCE_ICON_ATLAS, "Lumber", 0, 0, TRADE_UNITS, 0, TRADE_SELL_PRICE, 0, 0, 0),
            new Trade("Buy", RESOURCE_ICON_ATLAS, "Stone", TRADE_BUY_PRICE, 0, 0, 0, 0, 0, 0, TRADE_UNITS),
            new Trade("Sell", RESOURCE_ICON_ATLAS, "Stone", 0, 0, 0, TRADE_UNITS, TRADE_SELL_PRICE, 0, 0, 0),
    };

    // Trader (2026-08-22, user spec): same Wood/Stone trades as Exchange, Shards excluded, at
    // worse rates - "25% more to buy, 25% less for selling" read as the gold amount moving 25%
    // against the player at the same TRADE_UNITS=5 quantity, not the quantity changing.
    // 100 -> 125 buy, 80 -> 60 sell.
    private static final int TRADER_BUY_PRICE = Math.round(TRADE_BUY_PRICE * 1.25f);
    private static final int TRADER_SELL_PRICE = Math.round(TRADE_SELL_PRICE * 0.75f);
    private static final Trade[] TRADER_TRADES = {
            new Trade("Buy", RESOURCE_ICON_ATLAS, "Lumber", TRADER_BUY_PRICE, 0, 0, 0, 0, 0, TRADE_UNITS, 0),
            new Trade("Sell", RESOURCE_ICON_ATLAS, "Lumber", 0, 0, TRADE_UNITS, 0, TRADER_SELL_PRICE, 0, 0, 0),
            new Trade("Buy", RESOURCE_ICON_ATLAS, "Stone", TRADER_BUY_PRICE, 0, 0, 0, 0, 0, 0, TRADE_UNITS),
            new Trade("Sell", RESOURCE_ICON_ATLAS, "Stone", 0, 0, 0, TRADE_UNITS, TRADER_SELL_PRICE, 0, 0, 0),
    };

    public static void openExchangeDialog(MapStage stage, int objectId) {
        refreshExchangeDialog(stage, objectId);
        stage.showDialog();
    }

    private static void refreshExchangeDialog(MapStage stage, int objectId) {
        Dialog dialog = stage.getDialog();
        dialog.getContentTable().clear();
        dialog.getButtonTable().clear();
        dialog.clearListeners();

        AdventurePlayer player = AdventurePlayer.current();
        TypingLabel title = Controls.newTypingLabel("Exchange");
        title.skipToTheEnd();
        dialog.getContentTable().add(title).row();

        // Resource summary row, real icons instead of text words (2026-08-15 user request). Gold/
        // Shards use the standard "[+Name]" inline markup (registered on the shared font); Wood/
        // Stone can't - see ResourceDisplayActor's own class comment for why that markup silently
        // fails to resolve a picture for a second atlas - so they get real Image actors instead,
        // the same proven pattern buildTradeRow() below already uses for its own icons.
        Table summary = new Table();
        TypingLabel goldLabel = Controls.newTypingLabel("[+Gold]" + player.getGold());
        goldLabel.skipToTheEnd();
        summary.add(goldLabel).padRight(10f);
        TypingLabel shardsLabel = Controls.newTypingLabel("[+Shards]" + player.getShards());
        shardsLabel.skipToTheEnd();
        summary.add(shardsLabel).padRight(10f);
        Sprite lumberSprite = Config.instance().getAtlasSprite(RESOURCE_ICON_ATLAS, "Lumber");
        if (lumberSprite != null)
            summary.add(new Image(new TextureRegionDrawable(lumberSprite))).size(14f).padRight(2f);
        TypingLabel woodLabel = Controls.newTypingLabel(String.valueOf(player.getWood()));
        woodLabel.skipToTheEnd();
        summary.add(woodLabel).padRight(10f);
        Sprite stoneSprite = Config.instance().getAtlasSprite(RESOURCE_ICON_ATLAS, "Stone");
        if (stoneSprite != null)
            summary.add(new Image(new TextureRegionDrawable(stoneSprite))).size(14f).padRight(2f);
        TypingLabel stoneLabel = Controls.newTypingLabel(String.valueOf(player.getStone()));
        stoneLabel.skipToTheEnd();
        summary.add(stoneLabel);
        dialog.getContentTable().add(summary).padBottom(6f).row();

        // Compacted (user request 2026-08-09, "the Exchange menu is very big"): one row per
        // resource, Buy and Sell as two side-by-side buttons - the dialog is half as tall as the
        // old six-full-width-rows version. TRADES pairs stay (Buy, Sell) per resource, so step 2.
        // Both cells must each hold an actual TextraButton (showDialog()'s hard cast, see
        // buildTradeRow()); single-button rows below span both columns.
        for (int i = 0; i + 1 < TRADES.length; i += 2) {
            Trade buy = TRADES[i], sell = TRADES[i + 1];
            dialog.getButtonTable().add(buildTradeRow(buy.verb, TRADE_UNITS, buy.resourceAtlas, buy.resourceIcon,
                    TRADE_BUY_PRICE, buy.affordable(player), () -> {
                        buy.apply(player);
                        refreshExchangeDialog(stage, objectId);
                    })).width(118f);
            dialog.getButtonTable().add(buildTradeRow(sell.verb, TRADE_UNITS, sell.resourceAtlas, sell.resourceIcon,
                    TRADE_SELL_PRICE, sell.affordable(player), () -> {
                        sell.apply(player);
                        refreshExchangeDialog(stage, objectId);
                    })).width(118f).row();
        }
        TextraButton destroy = Controls.newTextButton("Destroy Building", () ->
                openDestroyConfirmDialog(stage, objectId, () -> refreshExchangeDialog(stage, objectId)));
        dialog.getButtonTable().add(destroy).colspan(2).width(240f).row();
        dialog.getButtonTable().add(Controls.newTextButton("Close", stage::hideDialog)).colspan(2).width(240f).row();
        dialog.setKeepWithinStage(true);
    }

    // Trader (2026-08-22) - same structure as Exchange above (Wood/Stone only, TRADER_TRADES'
    // worse rates), plus an "Upgrade to Exchange (Capitol only)" row - see
    // refreshTraderDialog()/upgradeTraderToExchange() below.
    public static void openTraderDialog(MapStage stage, int objectId) {
        refreshTraderDialog(stage, objectId);
        stage.showDialog();
    }

    private static void refreshTraderDialog(MapStage stage, int objectId) {
        Dialog dialog = stage.getDialog();
        dialog.getContentTable().clear();
        dialog.getButtonTable().clear();
        dialog.clearListeners();

        AdventurePlayer player = AdventurePlayer.current();
        TypingLabel title = Controls.newTypingLabel("Trader");
        title.skipToTheEnd();
        dialog.getContentTable().add(title).row();

        Table summary = new Table();
        TypingLabel goldLabel = Controls.newTypingLabel("[+Gold]" + player.getGold());
        goldLabel.skipToTheEnd();
        summary.add(goldLabel).padRight(10f);
        Sprite lumberSprite = Config.instance().getAtlasSprite(RESOURCE_ICON_ATLAS, "Lumber");
        if (lumberSprite != null)
            summary.add(new Image(new TextureRegionDrawable(lumberSprite))).size(14f).padRight(2f);
        TypingLabel woodLabel = Controls.newTypingLabel(String.valueOf(player.getWood()));
        woodLabel.skipToTheEnd();
        summary.add(woodLabel).padRight(10f);
        Sprite stoneSprite = Config.instance().getAtlasSprite(RESOURCE_ICON_ATLAS, "Stone");
        if (stoneSprite != null)
            summary.add(new Image(new TextureRegionDrawable(stoneSprite))).size(14f).padRight(2f);
        TypingLabel stoneLabel = Controls.newTypingLabel(String.valueOf(player.getStone()));
        stoneLabel.skipToTheEnd();
        summary.add(stoneLabel);
        dialog.getContentTable().add(summary).padBottom(6f).row();

        for (int i = 0; i + 1 < TRADER_TRADES.length; i += 2) {
            Trade buy = TRADER_TRADES[i], sell = TRADER_TRADES[i + 1];
            dialog.getButtonTable().add(buildTradeRow(buy.verb, TRADE_UNITS, buy.resourceAtlas, buy.resourceIcon,
                    TRADER_BUY_PRICE, buy.affordable(player), () -> {
                        buy.apply(player);
                        refreshTraderDialog(stage, objectId);
                    })).width(118f);
            dialog.getButtonTable().add(buildTradeRow(sell.verb, TRADE_UNITS, sell.resourceAtlas, sell.resourceIcon,
                    TRADER_SELL_PRICE, sell.affordable(player), () -> {
                        sell.apply(player);
                        refreshTraderDialog(stage, objectId);
                    })).width(118f).row();
        }
        // Upgrade to Exchange (2026-08-22, user spec, "Independent buildings" design): only ever
        // reachable on a Trader that's physically in the Capitol - a town Trader keeps working as
        // a Trader forever, on its own merits, with no cross-location state to track. Shown
        // (disabled, "Capitol only") on a town Trader while no Capitol exists yet, so the player
        // knows the feature exists and where they're headed - but once a Capitol IS established,
        // every OTHER town's Trader hides the row entirely (2026-08-22 user report): that specific
        // town can never become the Capitol, so a permanently-disabled button there is just
        // clutter, not a helpful pointer anymore. addButtonRow() was the wrong helper here (single
        // column, no colspan - left the button visibly off-center against the 2-wide Buy/Sell
        // grid above it and the colspan(2) Destroy/Close rows below); built directly instead.
        boolean isCapitol = TownRestoration.isCurrentTownCapitol();
        if (isCapitol || !TownRestoration.capitolExists()) {
            int[] exchangeCost = buildCostFor(EXCHANGE);
            // One-Exchange-per-town guard (2026-08-22 review fix): builtFlag(TRADER) gets cleared
            // by upgradeTraderToExchange() below (the slot is no longer a Trader), which otherwise
            // re-opens "build a Trader" in this same Capitol - a second Trader could then ALSO be
            // upgraded, silently overwriting the first Exchange's objectId mapping (orphaning it -
            // ShopActor's dispatch would fall through to a default and it'd revert to a plain
            // shop). Checking builtFlag(EXCHANGE) here closes that regardless of how many Traders
            // this Capitol ends up with.
            boolean alreadyHasExchange = stage.checkQuestFlag(builtFlag(EXCHANGE));
            boolean canUpgrade = isCapitol && !alreadyHasExchange
                    && canAffordCost(exchangeCost[0], exchangeCost[1], exchangeCost[2], exchangeCost[3]);
            String upgradeLabel = !isCapitol
                    ? "Upgrade to Exchange (Capitol only)"
                    : alreadyHasExchange
                            ? "Upgrade to Exchange (already built)"
                            : "Upgrade to Exchange (" + costLabel(exchangeCost[0], exchangeCost[1], exchangeCost[2], exchangeCost[3]) + ")";
            // 2026-08-22 review fix: Button.setDisabled() only changes the greyed-out drawable in
            // this UI framework - it does NOT stop the click listener from firing (every OTHER
            // gated button in this file already guards against this, see buildTradeRow() below).
            // This one didn't, so a tap on the visibly-disabled button opened the confirm dialog
            // and completed the upgrade anyway - bypassing the Capitol-only gate, or (if
            // unaffordable) driving gold/wood/stone negative via payCost()'s un-clamped deduction.
            TextraButton upgrade = Controls.newTextButton(upgradeLabel,
                    canUpgrade ? () -> openUpgradeToExchangeConfirmDialog(stage, objectId) : () -> {});
            upgrade.setDisabled(!canUpgrade);
            dialog.getButtonTable().add(upgrade).colspan(2).width(240f).row();
        }
        TextraButton destroy = Controls.newTextButton("Destroy Building", () ->
                openDestroyConfirmDialog(stage, objectId, () -> refreshTraderDialog(stage, objectId)));
        dialog.getButtonTable().add(destroy).colspan(2).width(240f).row();
        dialog.getButtonTable().add(Controls.newTextButton("Close", stage::hideDialog)).colspan(2).width(240f).row();
        dialog.setKeepWithinStage(true);
    }

    // Confirmation gate (same "Yes/No, act or return" idiom as openDestroyConfirmDialog) - pays
    // Exchange's own build cost (buildCostFor(EXCHANGE), same as building one fresh - an upgrade,
    // like Armory/Arena's, isn't free just because a Trader already stands here) and re-keys this
    // objectId from TRADER to EXCHANGE. Only reachable while canUpgrade was true at render time;
    // re-checks nothing on click since payCost()'s own contract is "caller gates first."
    private static void openUpgradeToExchangeConfirmDialog(MapStage stage, int objectId) {
        Dialog dialog = stage.getDialog();
        dialog.getContentTable().clear();
        dialog.getButtonTable().clear();
        dialog.clearListeners();
        int[] c = buildCostFor(EXCHANGE);
        addContentRow(dialog, "Upgrade this Trader to an Exchange for " + costLabel(c[0], c[1], c[2], c[3])
                + "?\nAdds Shards trading and better Wood/Stone rates. This cannot be undone.");
        addButtonRow(dialog, "Upgrade", true, () -> {
            upgradeTraderToExchange(stage, objectId);
            refreshExchangeDialog(stage, objectId);
        });
        addButtonRow(dialog, "Cancel", true, () -> refreshTraderDialog(stage, objectId));
        dialog.setKeepWithinStage(true);
    }

    private static void upgradeTraderToExchange(MapStage stage, int objectId) {
        PointOfInterestChanges changes = stage.getChanges();
        if (changes == null)
            return;
        int[] c = buildCostFor(EXCHANGE);
        payCost(c[0], c[1], c[2], c[3]);
        // Same cleanup destroyBuilding() uses for the old registration (remove-by-value, since
        // the map is type->objectId and a given objectId only ever holds one type at a time), NOT
        // a full destroy - shopRebuilt_<objectId> is deliberately left set, this building stays
        // built/functional throughout, just under a new type.
        changes.getEconomyBuildingObjectIds().values().removeIf(v -> v == objectId);
        changes.getMapFlags().remove(builtFlag(TRADER));
        changes.setEconomyBuildingObjectId(EXCHANGE, objectId);
        changes.getMapFlags().put(builtFlag(EXCHANGE), (byte) 1);
    }

    // One clickable trade row, e.g. "Buy 5 [shard icon] for 100 [gold icon]". MUST return an
    // actual TextraButton, not a generic Table/Actor - MapStage.showDialog() unconditionally
    // casts every dialog.getButtonTable() cell's actor to TextraButton (for gamepad/keyboard
    // focus navigation), so a plain Table there throws a ClassCastException every frame the
    // dialog is open (confirmed the hard way - see MOD_CHANGELOG.md). TextraButton extends
    // libGDX's own Button, which extends Table, so extra cells (the icons) can still just be
    // added directly onto the button itself after construction.
    private static TextraButton buildTradeRow(String verb, int qty, String resourceAtlas, String resourceIcon,
                                               int price, boolean enabled, Runnable action) {
        // Compact form (2026-08-09): "Buy 5 [icon] 100[gold]" at reduced scale, sized for two
        // trade buttons per dialog row - was a full-width "Buy 5 [icon] for 100 [gold]" per row.
        TextraButton button = Controls.newTextButton("[%85]" + verb + " " + qty, enabled ? action : () -> {});
        button.setDisabled(!enabled);
        // Controls.newTextButton()'s own label cell defaults to expand()+fill() (fine for a
        // plain single-label button, which is all this framework normally builds) - left as-is,
        // it greedily claims the whole button width, shoving every cell added below off to
        // the far right and leaving a big gap after "Buy 5"/"Sell 5". Disable that so the label
        // only takes its natural width and the icons sit right next to it.
        button.getTextraLabelCell().expand(false, false).fill(false, false);

        Sprite resourceSprite = Config.instance().getAtlasSprite(resourceAtlas, resourceIcon);
        if (resourceSprite != null)
            button.add(new Image(new TextureRegionDrawable(resourceSprite))).size(14f).padLeft(3f);
        button.add(Controls.newTypingLabel("[%85]" + price)).padLeft(4f);
        Sprite goldSprite = Config.instance().getAtlasSprite(Paths.ITEMS_ATLAS, "Gold");
        if (goldSprite != null)
            button.add(new Image(new TextureRegionDrawable(goldSprite))).size(14f).padLeft(3f);

        return button;
    }

    private static void addButtonRow(Dialog dialog, String name, boolean enabled, Runnable action) {
        TextraButton button = Controls.newTextButton(name, enabled ? action : () -> {});
        button.setDisabled(!enabled);
        dialog.getButtonTable().add(button).width(240f).row();
    }

    // Half-width buttons packed 2 per row (Manage Guards dialog, user request 2026-08-11 - see
    // its own comment). `column` is a 1-element int[] used as a mutable counter across calls -
    // starts a new row every 2nd button; finishHalfButtonRow() closes a dangling odd row (an odd
    // guard count, e.g. 1 hired at a town) so the NEXT addHalfButton() call starts fresh at
    // column 0 instead of silently continuing an old row.
    private static void addHalfButton(Dialog dialog, int[] column, String name, boolean enabled, Runnable action) {
        TextraButton button = Controls.newTextButton(name, enabled ? action : () -> {});
        button.setDisabled(!enabled);
        // Widened 118 -> 140 (round 5 bug fix, alongside the [%75] scale + "/wk" abbreviation at
        // this method's only two call sites) - 118 was already marginal for "Hire <tier> (<cost>)"
        // before resource icons existed, and clearly overflowing after.
        Cell<TextraButton> cell = dialog.getButtonTable().add(button).width(140f);
        column[0]++;
        if (column[0] % 2 == 0)
            cell.row();
    }

    private static void finishHalfButtonRow(Dialog dialog, int[] column) {
        if (column[0] % 2 != 0) {
            dialog.getButtonTable().row();
            column[0] = 0;
        }
    }

    // ---- Daily production / weekly interest sweep, driven by WorldStage.onActing() whenever
    // World's day counter advances (see WorldStage.java). ----

    public static void processDaysPassed(int daysPassed, int newDayCount) {
        if (daysPassed <= 0)
            return;
        // Progressive Set Unlocks (MOD_SCOPE.md #4): player-level, not per-town, so this lives
        // outside the per-town loop below - unlocks the edition the moment the 7-day timer
        // elapses, not only when the player happens to revisit the Lab.
        AdventurePlayer.current().checkResearchCompletion(newDayCount);
        for (PointOfInterestChanges changes : WorldSave.getCurrentSave().getAllPointOfInterestChanges()) {
            // A town can now have several economy buildings at once (one of each type) - process
            // every type it actually has, not just a single registered building. Iteration order
            // here doesn't matter - unlike guard salaries below, mine production/bank interest
            // never compete with another town for a shared resource.
            for (int type : changes.getEconomyBuildingObjectIds().keySet()) {
                if (isProducingType(type)) {
                    // Mine weekly payout (2026-08-16, user spec: moved off the old flat
                    // RESOURCE_PRODUCTION_PER_DAY-per-day rate onto the SAME fixed shared payday
                    // schedule Guards/Bank interest already use - day 7, 14, 21, ... regardless of
                    // this specific mine's own build day, not a rolling "N days since last payout"
                    // timer. A mine built day 3 has lastPayoutDay=3, so its first nextPayday here
                    // is still day 7. Identical while-loop shape to the guard-salary pass below so
                    // a long fast-forward that skips several paydays at once still pays each one.
                    int lastPaid = changes.getEconomyBuildingLastPayoutDay(type);
                    while (true) {
                        int nextPayday = ((lastPaid / 7) + 1) * 7;
                        if (nextPayday > newDayCount)
                            break;
                        int amount = mineWeeklyAmount(type);
                        switch (type) {
                            case SHARD_MINE: AdventurePlayer.current().addShards(amount); break;
                            case GOLD_MINE:
                                // "Gold Mine deposits into Bank Directly" (2026-08-13, user spec) -
                                // only when THIS town actually has a Bank built; otherwise falls
                                // back to the player's own gold same as always.
                                if (AdventurePlayer.current().isGoldMineDepositsToBankDirectly() && changes.hasEconomyBuildingOfType(BANK))
                                    changes.addBankBalance(amount);
                                else
                                    AdventurePlayer.current().giveGold(amount);
                                break;
                            case LUMBER_MILL: AdventurePlayer.current().addWood(amount); break;
                            case STONE_MINE: AdventurePlayer.current().addStone(amount); break;
                        }
                        lastPaid = nextPayday;
                    }
                    changes.setEconomyBuildingLastPayoutDay(type, lastPaid);
                } else if (type == BANK && changes.getBankBalance() > 0) {
                    int periodsBefore = (newDayCount - daysPassed - 1) / INTEREST_PERIOD_DAYS;
                    int periodsAfter = (newDayCount - 1) / INTEREST_PERIOD_DAYS;
                    for (int i = periodsBefore; i < periodsAfter; i++) {
                        int interest = Math.round(changes.getBankBalance() * INTEREST_RATE);
                        if (interest > 0)
                            changes.addBankBalance(interest);
                    }
                }
            }
        }
        // Guard weekly salary (2026-08-11, MOD_SCOPE.md #22) - a separate pass, deliberately not
        // folded into the per-town loop above: guard salaries draw on the player's own shared gold/
        // shard inventory (and now, optionally, a town's bank - see payGuardGold()), so unlike mine
        // production/interest, PROCESSING ORDER matters here. Capitol-priority ordering (2026-08-13,
        // user spec): the Capitol's own guards are paid first, then every other town with a guard in
        // order of increasing distance from the Capitol - see townsByCapitolPriority(). Back-to-front
        // per town so a mid-loop disband (removeGuardAt) doesn't skip the next guard.
        for (PointOfInterest poi : townsByCapitolPriority()) {
            PointOfInterestChanges changes = WorldSave.getCurrentSave().peekPointOfInterestChanges(poi.getID());
            if (changes == null || changes.getGuardCount() == 0)
                continue;
            for (int i = changes.getGuardCount() - 1; i >= 0; i--) {
                String tier = changes.getGuardTier(i);
                int lastPaid = changes.getGuardLastPaidDay(i);
                boolean disbanded = false;
                // Fixed shared payday (2026-08-13, user spec): every guard pays on the same
                // calendar days - 7, 14, 21, 28, ... - regardless of its own hire/last-paid day,
                // instead of a per-guard rolling "7 days since I was last paid" timer. The next due
                // day is the smallest multiple of 7 strictly greater than lastPaid; guards hired
                // mid-cycle (or carried over from before this change, when lastPaid values weren't
                // multiples of 7) snap onto the shared schedule automatically the next time this
                // rounds up - no save migration needed. A while loop (not a single if) so a long
                // fast-forward that skips several paydays at once still charges/disbands correctly
                // for each one, same reasoning as the Bank interest periods above.
                while (true) {
                    int nextPayday = ((lastPaid / 7) + 1) * 7;
                    if (nextPayday > newDayCount)
                        break;
                    int goldCost = guardWeeklyGoldCost(tier);
                    int shardCost = guardWeeklyShardCost(tier);
                    // Shards (Grandmaster/Mythic tier only) always come straight from the player's
                    // own inventory, untouched by the Bank preference (user spec) - checked first,
                    // side-effect-free, so a shard shortfall never leaves gold half-spent below.
                    if (AdventurePlayer.current().getShards() >= shardCost && payGuardGold(changes, goldCost)) {
                        if (shardCost > 0)
                            AdventurePlayer.current().takeShards(shardCost);
                        lastPaid = nextPayday;
                    } else {
                        GameHUD.getInstance().addNotification("[RED]Your " + guardTierDisplayName(tier)
                                + " guard was disbanded - salary went unpaid!", true);
                        changes.removeGuardAt(i);
                        disbanded = true;
                        break;
                    }
                }
                if (!disbanded)
                    changes.setGuardLastPaidDay(i, lastPaid);
            }
        }
    }

    /** Every POI, Capitol first (if one exists) then every other town in order of increasing
     *  distance from it - see processDaysPassed()'s guard-salary pass. No Capitol yet: natural
     *  POI order (nothing to prioritize against). */
    private static List<PointOfInterest> townsByCapitolPriority() {
        // Every POI on the map, not just towns (dungeons/caves included) - harmless, since the
        // guard-salary loop below immediately skips anything with no recorded guards.
        List<PointOfInterest> pois = new ArrayList<>(WorldSave.getCurrentSave().getWorld().getAllPointOfInterest());
        PointOfInterest capitol = TownRestoration.findCapitol();
        if (capitol == null)
            return pois;
        pois.sort(Comparator.comparingDouble(poi ->
                poi == capitol ? -1 : poi.getPosition().dst2(capitol.getPosition())));
        return pois;
    }

    /** Pays a guard's weekly Gold cost, split between the guard's own town's bank and the player's
     *  inventory per AdventurePlayer.isPayGuardsFromBankFirst() (user spec, 2026-08-13): checked
     *  drains the town's bank before the player's gold, unchecked drains the player's gold before
     *  the bank. Only ever touches THIS guard's own town's bank (today that's a no-op source for
     *  any non-Capitol town, since Bank can only be built in the Capitol - see buildChooseBuildingDialog()).
     *  Returns false (nothing moved) if bank+inventory combined can't cover goldCost. */
    private static boolean payGuardGold(PointOfInterestChanges changes, int goldCost) {
        if (goldCost <= 0)
            return true;
        AdventurePlayer player = AdventurePlayer.current();
        // Guard against a destroyed Bank's orphaned balance (destroyBuilding() never zeroes
        // bankBalance) becoming an invisible-but-still-spendable slush fund - same
        // hasEconomyBuildingOfType(BANK) gate the Gold Mine deposit branch above already uses.
        int bankAvailable = changes.hasEconomyBuildingOfType(BANK) ? changes.getBankBalance() : 0;
        int inventoryAvailable = player.getGold();
        if (bankAvailable + inventoryAvailable < goldCost)
            return false;
        int fromBank, fromInventory;
        if (player.isPayGuardsFromBankFirst()) {
            fromBank = Math.min(bankAvailable, goldCost);
            fromInventory = goldCost - fromBank;
        } else {
            fromInventory = Math.min(inventoryAvailable, goldCost);
            fromBank = goldCost - fromInventory;
        }
        if (fromBank > 0)
            changes.addBankBalance(-fromBank);
        if (fromInventory > 0)
            player.takeGold(fromInventory);
        return true;
    }
}
