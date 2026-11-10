package forge.adventure.util;

import com.badlogic.gdx.scenes.scene2d.Actor;
import com.github.tommyettinger.textra.TextraButton;
import forge.adventure.player.AdventurePlayer;

import java.util.function.IntSupplier;

/**
 * Round 999 (the tooltip audit's round B, spots 16-22 and 37-46): hover tips in towns, shops, the Shard Trader, the
 * Spellsmith and the Inn. Each screen hands its widgets over in one call; every text is a tips.json key, its numbers
 * filled where the game reads them (the tuning table, the screen's own live price), never written into the text.
 * A tip inside a map dialog (the Job Board, the restore offer) names the town map as its scene: the HUD's null scene
 * stands down while a dialog holds the input. [TFR-Tips].
 */
public final class TfrPlaceTips {
    private TfrPlaceTips() {
    }

    private static Object townScene() {
        return forge.adventure.scene.TileMapScene.instance();
    }

    // ------------------------------------------------------------------ shops (RewardScene)

    /** RewardScene's constructor: its TFR buttons. restockPrice is the price the Restock button charges now. */
    public static void shop(Object scene, Actor restock, Actor armoryReroll, Actor blueprint, Actor shopType,
                            Actor upgradeArmory, Actor manageGuards, Actor storage, Actor destroy, IntSupplier restockPrice,
                            IntSupplier armoryRerollCost) {
        if (!TfrTips.enabled())
            return;
        TfrTips.region(scene, restock, "shop.restock", () -> TfrTips.text("shop.restock", "price", restockPrice.getAsInt()));
        TfrTips.region(scene, armoryReroll, "shop.armoryReroll",
                () -> TfrTips.text("shop.armoryReroll", "cost", armoryRerollCost.getAsInt()));
        TfrTips.region(scene, blueprint, "shop.blueprint");
        TfrTips.region(scene, shopType, "shop.shopType");
        TfrTips.region(scene, upgradeArmory, "shop.upgradeArmory");
        TfrTips.region(scene, manageGuards, "shop.manageGuards");
        TfrTips.region(scene, storage, "shop.storage");
        TfrTips.region(scene, destroy, "shop.destroy");
        System.out.println("[TFR-Tips] the shop's tips registered");
    }

    /** RewardScene.BuyButton: the price under a card or item in a shop. */
    public static void price(Object scene, Actor buyButton) {
        if (!TfrTips.enabled())
            return;
        TfrTips.region(scene, buyButton, "shop.price", () -> {
            float f = Ascendance.shopPriceFactor();
            return TfrTips.text("shop.price", "haggler", f < 1f
                    ? TfrTips.text("shop.haggler", "pct", TfrTips.num(Math.round((1f - f) * 1000f) / 10f)) : "");
        });
    }

    // ------------------------------------------------------------------ the Shard Trader

    /** ShardTraderScene's constructor: the buy and sell buttons with the amounts they trade. */
    public static void shardTrader(Object scene, Actor buy, Actor sell, IntSupplier buyShards, IntSupplier buyGold,
                                   IntSupplier sellShards, IntSupplier sellGold) {
        if (!TfrTips.enabled())
            return;
        TfrTips.region(scene, buy, "shardTrader.buy",
                () -> TfrTips.text("shardTrader.buy", "n", buyShards.getAsInt(), "gold", buyGold.getAsInt()));
        TfrTips.region(scene, sell, "shardTrader.sell", () -> {
            float ratio = AdventurePlayer.current() == null || AdventurePlayer.current().getDifficulty() == null ? 1f
                    : AdventurePlayer.current().getDifficulty().shardSellRatio;
            return TfrTips.text("shardTrader.sell", "n", sellShards.getAsInt(), "gold", sellGold.getAsInt(),
                    "pct", TfrTips.num(Math.round(ratio * 100f)));
        });
    }

    // ------------------------------------------------------------------ the Spellsmith

    /** SpellSmithScene's constructor: the filters and the pull buttons, with the multipliers its price uses. */
    public static void spellsmith(Object scene, Actor edition, Iterable<? extends Actor> colors, Iterable<? extends Actor> rarities,
                                  Iterable<? extends Actor> costs, Actor pullGold, Actor pullShards, float editionMult,
                                  float colorMult, float colorMax, float[] rarityMult, float costMult, float shardShare) {
        if (!TfrTips.enabled())
            return;
        TfrTips.region(scene, edition, "smith.edition", () -> TfrTips.text("smith.edition", "mult", TfrTips.num(editionMult)));
        for (Actor a : colors)
            TfrTips.region(scene, a, "smith.color", () -> TfrTips.text("smith.color", "mult", TfrTips.num(colorMult),
                    "two", TfrTips.num(Math.min(2 * colorMult, colorMax)), "max", TfrTips.num(colorMax)));
        for (Actor a : rarities)
            TfrTips.region(scene, a, "smith.rarity", () -> TfrTips.text("smith.rarity", "common", TfrTips.num(rarityMult[0]),
                    "uncommon", TfrTips.num(rarityMult[1]), "rare", TfrTips.num(rarityMult[2]), "mythic", TfrTips.num(rarityMult[3])));
        for (Actor a : costs)
            TfrTips.region(scene, a, "smith.cost", () -> TfrTips.text("smith.cost", "mult", TfrTips.num(costMult)));
        TfrTips.region(scene, pullGold, "smith.pull", () -> TfrTips.text(paysFirst() ? "smith.pullPaid" : "smith.pull"));
        TfrTips.region(scene, pullShards, "smith.pullShards", () -> TfrTips.text(paysFirst() ? "smith.pullPaid" : "smith.pull")
                + "\n" + TfrTips.text("smith.shards", "pct", TfrTips.num(Math.round(shardShare * 100f))));
        System.out.println("[TFR-Tips] the Spellsmith's tips registered");
    }

    private static boolean paysFirst() {
        try {
            return Config.instance().getConfigData().spellsmithPaysFirst;
        } catch (RuntimeException e) {
            return false;
        }
    }

    // ------------------------------------------------------------------ the Inn

    /** InnScene's constructor: Temporary Hit Points (its tip follows the button's state word), Sell and Re-roll. */
    public static void inn(Object scene, TextraButton tempHp, Actor sell, Actor reroll) {
        if (!TfrTips.enabled())
            return;
        TfrTips.region(scene, tempHp, "inn.tempHp", () -> {
            String shown = tempHp.getText() == null ? "" : tempHp.getText().toString();
            return TfrTips.text(shown.contains("Barred") ? "inn.tempHpBarred" : shown.contains("Blessed") ? "inn.tempHpBlessed"
                    : shown.contains("Closed") ? "inn.tempHpClosed" : "inn.tempHp");
        });
        TfrTips.region(scene, sell, "inn.sell");
        TfrTips.region(scene, reroll, "inn.reroll");
    }

    /** MenuScene.loadDialog: an Inn event's "Spend N" entry option. Other menus' options get no tip. */
    public static void menuOption(Object scene, Actor button, String optionName) {
        if (!TfrTips.enabled() || optionName == null || !(scene instanceof forge.adventure.scene.EventScene))
            return;
        if (optionName.startsWith("Spend "))
            TfrTips.region(scene, button, "inn.entryFee", () -> {
                float f = Ascendance.innFeeFactor();
                return TfrTips.text("inn.entryFee", "regular", f < 1f ? TfrTips.text("inn.regular", "factor", TfrTips.num(f)) : "");
            });
    }

    // ------------------------------------------------------------------ towns: the Job Board

    /** MapDialog: a map dialog's option button; the restore offer gets its tip. */
    public static void mapOption(Actor button, String optionName) {
        if (!TfrTips.enabled() || optionName == null)
            return;
        if (optionName.startsWith("Restore town"))
            TfrTips.region(townScene(), button, "town.restore", () -> TfrTips.text("town.restore",
                    "rep", TownRestoration.RESTORE_REPUTATION,
                    "slots", TownRestoration.RESTORE_REPUTATION * TownRestoration.BUILDINGS_PER_REPUTATION));
    }

    /** TownRestoration.openJobBoardMenu: the Upgrade to Capitol button in one of its three states. */
    public static void capitol(Actor button, String state, int have, int need) {
        if (!TfrTips.enabled())
            return;
        TfrTips.region(townScene(), button, "town.capitol", () -> {
            String base = TfrTips.text("town.capitol", "mages", Config.instance().getTuningData().capitolBuiltSendsMages
                    ? TfrTips.text("town.capitolMages") : "");
            if ("orazca".equals(state))
                return TfrTips.text("town.capitolOrazca") + "\n" + base;
            if ("towns".equals(state))
                return TfrTips.text("town.capitolTowns", "need", need, "have", have) + "\n" + base;
            return base;
        });
    }

    /** TownSpecialties.addJobBoardRow: the specialty row - "choose" (free), "locked" or "change"; baseFee as the button's
     *  cost label takes it (it scales by the difficulty and the build-cost perk, as EconomyBuildings.costLabel does). */
    public static void specialty(Actor button, String state, int baseFee, int daysLeft) {
        if (!TfrTips.enabled())
            return;
        int fee = Math.round(EconomyBuildings.scaledCost(baseFee) * Ascendance.buildCostFactor());
        String key = "locked".equals(state) ? "town.specialtyLocked" : "change".equals(state) ? "town.specialtyChange"
                : "town.specialtyChoose";
        TfrTips.region(townScene(), button, key, () -> TfrTips.text(key, "fee", fee, "days", daysLeft));
    }

    /** RealmMilestones.addJobBoardRow: the realm line, or the waiting boon's button. */
    public static void realm(Actor button, int every) {
        if (!TfrTips.enabled())
            return;
        TfrTips.region(townScene(), button, "town.realm", () -> TfrTips.text("town.realm", "every", every));
    }
}
