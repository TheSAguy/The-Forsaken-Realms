package forge.adventure.scene;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.controllers.Controller;
import com.badlogic.gdx.controllers.Controllers;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.Timer;
import com.github.tommyettinger.textra.TextraButton;
import com.github.tommyettinger.textra.TextraLabel;
import com.github.tommyettinger.textra.TypingLabel;
import forge.Forge;
import forge.adventure.character.ShopActor;
import forge.adventure.stage.MapStage;
import forge.adventure.util.MapDialog;
import forge.haptic.HapticEngine;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.adventure.data.RewardData;
import forge.adventure.data.ShopData;
import forge.adventure.data.WorldData;
import forge.adventure.player.AdventurePlayer;
import forge.adventure.pointofintrest.PointOfInterestChanges;
import forge.adventure.stage.GameHUD;
import forge.adventure.util.*;
import forge.adventure.world.WorldSave;
import forge.assets.ImageCache;
import forge.deck.Deck;
import forge.item.PaperCard;
import forge.sound.SoundEffectType;
import forge.sound.SoundSystem;
import forge.util.ItemPool;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Displays the rewards of a fight or a treasure
 */
public class RewardScene extends UIScene {
    private TextraButton doneButton, detailButton, restockButton, destroyButton, guardsButton, upgradeButton, rerollButton, shopTypeRerollButton, buyBlueprintButton;
    private TextraLabel playerGold, playerShards;
    private TypingLabel headerLabel;
    private Vector2 headerLabelOrigPos;
    private boolean autoSell;

    private ShopActor shopActor;
    private static RewardScene object;

    private PointOfInterestChanges changes;

    public static RewardScene instance() {
        if (object == null)
            object = new RewardScene();
        return object;
    }

    public enum Type {
        Shop,
        Loot,
        QuestReward,
        RewardChoice
    }

    Type type;
    Array<Actor> generated = new Array<>();
    static public final float CARD_WIDTH = 550f;
    static public final float CARD_HEIGHT = 400f;
    static public final float CARD_WIDTH_TO_HEIGHT = CARD_WIDTH / CARD_HEIGHT;
    ItemPool<PaperCard> collectionPool = null;
    private int remainingSelections = 0;
    // Priced RewardChoice (2026-08-25, Chest's Thief Merchant event - user spec: "not free... at
    // 0.75x their normal value"). 0 keeps the original free behavior every other RewardChoice
    // caller (MapDialog's quest-authored grantRewardsChoice) relies on - explicit per-call so
    // nothing can silently inherit a stale multiplier from RewardScene's singleton state.
    private float selectionPriceMultiplier = 0f;

    private RewardScene() {
        super(Forge.isLandscapeMode() ? "ui/items.json" : "ui/items_portrait.json");

        playerGold = Controls.newAccountingLabel(ui.findActor("playerGold"), false);
        playerShards = Controls.newAccountingLabel(ui.findActor("playerShards"), true);
        headerLabel = ui.findActor("shopName");
        headerLabelOrigPos = new Vector2(headerLabel.getX(), headerLabel.getY());
        ui.onButtonPress("done", this::done);
        ui.onButtonPress("detail", this::toggleToolTip);
        ui.onButtonPress("restock", this::restockShop);
        detailButton = ui.findActor("detail");
        detailButton.setVisible(false);
        doneButton = ui.findActor("done");
        restockButton = ui.findActor("restock");
        // Destroy Building lives ON the shop page (mod feature, user revision 2026-08-09 - see
        // ShopActor.isDestroyable() for which shops qualify) - built programmatically rather than
        // added to the shared ui/items.json, which every plane's shops load. Positioned above the
        // done/checkmark button.
        destroyButton = Controls.newTextButton("Destroy Building", this::promptDestroyShop);
        destroyButton.setSize(doneButton.getWidth() * 2.2f, doneButton.getHeight() * 0.8f);
        destroyButton.setPosition(doneButton.getX() + doneButton.getWidth() - destroyButton.getWidth(),
                doneButton.getY() + doneButton.getHeight() + 10f);
        destroyButton.setVisible(false);
        ui.addActor(destroyButton);
        // Manage Guards (mod feature, user spec 2026-08-11, MOD_SCOPE.md #22) - Armory-only, Level
        // 2 only. Same programmatic-button pattern as Destroy Building above, positioned one row
        // higher so both can be visible at once (an Armory that's both Level 2 and destroyable).
        guardsButton = Controls.newTextButton("Manage Guards", this::promptManageGuards);
        guardsButton.setSize(doneButton.getWidth() * 2.2f, doneButton.getHeight() * 0.8f);
        guardsButton.setPosition(doneButton.getX() + doneButton.getWidth() - guardsButton.getWidth(),
                doneButton.getY() + doneButton.getHeight() * 2 + 20f);
        guardsButton.setVisible(false);
        ui.addActor(guardsButton);
        // Upgrade to Level 2 (mod feature, user spec 2026-08-11, Task #8/#13) - Armory-only,
        // Level 1 only (mutually exclusive with Manage Guards, same row/position - a shop is never
        // both at once). 2026-08-12 cost table: 300 stone.
        upgradeButton = Controls.newTextButton("[%80]Upgrade Armory (" + EconomyBuildings.costLabel(0, 0, EconomyBuildings.ARMORY_UPGRADE_STONE, 0) + ")", this::promptUpgradeArmory);
        upgradeButton.setSize(doneButton.getWidth() * 2.2f, doneButton.getHeight() * 0.8f);
        upgradeButton.setPosition(doneButton.getX() + doneButton.getWidth() - upgradeButton.getWidth(),
                doneButton.getY() + doneButton.getHeight() * 2 + 20f);
        upgradeButton.setVisible(false);
        ui.addActor(upgradeButton);
        // Re-roll Inventory (mod feature, user spec 2026-08-11, round 7) - Armory-only, any level
        // (unlike guardsButton/upgradeButton, this isn't level-gated - even a Level 1 Armory has a
        // noRestock inventory that can be re-rolled). Own row, 3 above doneButton, since it can be
        // visible at the same time as EITHER guardsButton or upgradeButton (whichever the level
        // allows) - never with both at once, so 3 rows total is enough, no dynamic stacking needed.
        rerollButton = Controls.newTextButton("[%80]Re-roll Inventory (" + EconomyBuildings.scaledCost(EconomyBuildings.ARMORY_REROLL_SHARD_COST) + " [+Shards])", this::promptRerollArmory);
        rerollButton.setSize(doneButton.getWidth() * 2.2f, doneButton.getHeight() * 0.8f);
        rerollButton.setPosition(doneButton.getX() + doneButton.getWidth() - rerollButton.getWidth(),
                doneButton.getY() + doneButton.getHeight() * 3 + 30f);
        rerollButton.setVisible(false);
        ui.addActor(rerollButton);
        // Shop Type Re-Roll (mod feature, user spec 2026-08-11, round 8) - ordinary card shops
        // only. Reuses guardsButton/upgradeButton's row position: those are Armory-only, this is
        // Armory-exclusive (a shop resolves to exactly one ShopData at a time), so they never
        // need to show at the same time.
        // Renamed from "Re-roll Shop Type" and re-costed 2026-08-30 (user spec): it no longer
        // rolls a random type for a flat shard fee - it opens the same tier/category chooser the
        // rebuild menu uses, and you pay the chosen shop's tier price minus 50% of the current
        // shop's gold. The old flat SHOP_TYPE_REROLL_SHARD_COST no longer applies, so the button
        // carries no price label: the price depends on what you pick.
        shopTypeRerollButton = Controls.newTextButton("[%80]Re-assign Shop Type", this::promptRerollShopType);
        shopTypeRerollButton.setSize(doneButton.getWidth() * 2.2f, doneButton.getHeight() * 0.8f);
        shopTypeRerollButton.setPosition(doneButton.getX() + doneButton.getWidth() - shopTypeRerollButton.getWidth(),
                doneButton.getY() + doneButton.getHeight() * 2 + 20f);
        shopTypeRerollButton.setVisible(false);
        ui.addActor(shopTypeRerollButton);
        // Buy Blueprint (user spec 2026-08-30): learn the type of the shop you are STANDING IN.
        // Deliberately not a menu of unknown types - buying the shop in front of you makes AI-town
        // exploration the acquisition loop, is self-documenting, and cannot be farmed from one
        // spot.
        //
        // ROW 3, not row 4 (2026-08-31 fix). User report: "There was no Buy Blue-print button on
        // any of the AI shops, Neutral or 5 colors" - the button was being made visible correctly
        // all along, it was just positioned off the top of the screen. The layout is 480x270 and
        // doneButton sits at stage y=120 with height 30, so the rows land at y=160/200/240/280 -
        // and row 4's 280 is past the 270-unit ceiling entirely, minus its own height on top of
        // that. Row 3 (y=240) is the highest row that actually fits.
        //
        // Sharing row 3 with rerollButton is safe: that one is Armory-only (armoryFeatures) and
        // this one is explicitly !isArmory, so a shop resolves to at most one of them - the same
        // mutual exclusion guardsButton/upgradeButton/shopTypeRerollButton already rely on for
        // row 2.
        buyBlueprintButton = Controls.newTextButton("[%80]Buy Blueprint", this::promptBuyBlueprint);
        buyBlueprintButton.setSize(doneButton.getWidth() * 2.2f, doneButton.getHeight() * 0.8f);
        buyBlueprintButton.setPosition(doneButton.getX() + doneButton.getWidth() - buyBlueprintButton.getWidth(),
                doneButton.getY() + doneButton.getHeight() * 3 + 30f);
        buyBlueprintButton.setVisible(false);
        ui.addActor(buyBlueprintButton);
    }

    /**
     * Buys the blueprint for the shop type the player is currently standing in (user spec
     * 2026-08-30). Priced in SHARDS by the shop's own tier - 20 common / 40 uncommon / 100 rare.
     * Shown at ANY shop whose type the player does not yet know, player-owned or AI: the point is
     * that crawling rival towns is how you find new types. Hidden entirely once known, so a shop
     * you have already learned looks exactly as it did before this feature.
     */
    private void promptBuyBlueprint() {
        if (shopActor == null || shopActor.getShopData() == null)
            return;
        final String shopName = shopActor.getShopData().name;
        if (EconomyBuildings.isShopTypeUnlocked(shopName)
                || EconomyBuildings.isBasicLandShop(shopActor.getShopData()))
            return; // already known, or a Cartographer - button should not have been visible
        final String tier = EconomyBuildings.shopTierOf(shopActor.getMapStage(), shopActor.getObjectId(), shopName);
        // Re-checked here, not just on the button: this framework's setDisabled() does NOT detach
        // click handlers (the negative-gold lesson, round 44 and twice since), so a greyed button
        // is still clickable and a gamepad can still focus it.
        final String block = EconomyBuildings.blueprintStandingBlock(tier);
        if (block != null) {
            showDialog(createGenericDialog("", block,
                    Forge.getLocalizer().getMessage("lblOK"), null, this::removeDialog, null));
            return;
        }
        final int cost = EconomyBuildings.blueprintShardCostHere(tier);
        if (AdventurePlayer.current().getShards() < cost) {
            showDialog(createGenericDialog("", "A blueprint for " + EconomyBuildings.shopDisplayName(shopName)
                            + " costs " + cost + " [+Shards] - you have " + AdventurePlayer.current().getShards() + ".",
                    Forge.getLocalizer().getMessage("lblOK"), null, this::removeDialog, null));
            return;
        }
        showDialog(createGenericDialog("", "Buy the blueprint for "
                        + EconomyBuildings.shopDisplayName(shopName) + " for " + cost
                        + " [+Shards]?\nYou will be able to build this shop type in your own towns.",
                Forge.getLocalizer().getMessage("lblYes"), Forge.getLocalizer().getMessage("lblNo"), () -> {
                    removeDialog();
                    // Re-check inside the handler: this framework's setDisabled() does not detach
                    // click handlers (the negative-gold lesson), and shards can change between
                    // the dialog opening and the confirm.
                    if (AdventurePlayer.current().getShards() < cost
                            || EconomyBuildings.isShopTypeUnlocked(shopName)
                            || EconomyBuildings.blueprintStandingBlock(tier) != null)
                        return;
                    AdventurePlayer.current().takeShards(cost);
                    AdventurePlayer.current().unlockShopType(shopName, "blueprint bought at " + shopName
                            + " for " + cost + " shards (tier=" + tier + ")");
                    HapticEngine.vibrate(FPref.UI_VIBRATE_ON_SHOP_ACTION, 5);
                    SoundSystem.instance.play(SoundEffectType.Shuffle, false);
                    buyBlueprintButton.setVisible(false); // known now - nothing left to buy here
                }, this::removeDialog));
    }

    /** Opens the tier/category chooser for an already-built card shop (2026-08-30 user spec,
     *  replacing the old flat random re-roll for 50 shards). The shop screen closes first:
     *  changing type replaces this shop's identity AND its whole inventory, so the page behind
     *  the dialog would be stale, and the chooser is a MapStage dialog rather than a RewardScene
     *  one. Price, the 50%-of-old-shop gold credit, the Capitol gate on Rare tiers and per-entry
     *  affordability all live inside the chooser itself - this method just routes to it. */
    private void promptRerollShopType() {
        if (shopActor == null || changes == null)
            return;
        // Defense-in-depth (2026-08-13, AI-town gate) - the button itself is already hidden at
        // AI towns via shopTypeRerollButton's own playerOwnedTown check in loadRewards(), this
        // guards against any other path (e.g. controller/gamepad focus) still invoking it.
        if (!TownRestoration.isCurrentTownPlayerOwned(changes))
            return;
        MapStage stage = shopActor.getMapStage();
        int objectId = shopActor.getObjectId();
        if (!stage.isShopTypeRerollable(objectId))
            return;
        String currentName = shopActor.getShopData() == null ? null : shopActor.getShopData().name;
        MapDialog dialog = EconomyBuildings.buildReassignShopTypeDialog(stage, objectId, currentName);
        if (dialog == null)
            return; // nothing this shop could become - no dialog shown, nothing charged
        Forge.switchToLast();
        if (dialog.activate())
            stage.showDialog();
    }

    private void promptManageGuards() {
        if (shopActor == null || changes == null)
            return;
        // Defense-in-depth (2026-08-13, AI-town gate) - guardsButton is already hidden at AI
        // towns (only reachable via armoryFeatures' playerOwnedTown check), this guards against
        // any other path still invoking it.
        if (!TownRestoration.isCurrentTownPlayerOwned(changes))
            return;
        forge.adventure.pointofintrest.PointOfInterest rootPoint = TileMapScene.instance().rootPoint;
        String poiName = rootPoint == null ? null : rootPoint.getData().name;
        EconomyBuildings.openManageGuardsDialog(this, changes, poiName, shopActor.getObjectId());
    }

    private void promptUpgradeArmory() {
        if (shopActor == null || changes == null)
            return;
        // Defense-in-depth (2026-08-13, AI-town gate: "only the player can build/upgrade stuff")
        // - upgradeButton is already hidden at AI towns (only reachable via armoryFeatures'
        // playerOwnedTown check), this guards against any other path still invoking it. This is
        // the exact button that was live and exploitable at the 5 AI capitals before today.
        if (!TownRestoration.isCurrentTownPlayerOwned(changes))
            return;
        // 2026-08-12 cost table: Armory upgrade is 300 stone.
        if (!EconomyBuildings.canAffordCost(0, 0, EconomyBuildings.ARMORY_UPGRADE_STONE, 0))
            return;
        showDialog(createGenericDialog("", "Upgrade this Armory to Level 2 for "
                        + EconomyBuildings.costLabel(0, 0, EconomyBuildings.ARMORY_UPGRADE_STONE, 0)
                        + "?\nUnlocks the ability to hire guards to defend this town.",
                Forge.getLocalizer().getMessage("lblYes"), Forge.getLocalizer().getMessage("lblNo"), () -> {
                    removeDialog();
                    // Resolve the L2-suffixed ShopData BEFORE charging anything (2026-08-13 fix,
                    // adversarial review finding: some armory-family shops - the 5 AI-capital
                    // colored Equipment/Items pairs, e.g. RedEquipment/RedItems - have no *L2
                    // shops.json sibling at all, a pre-existing data gap MapStage.loadMap()'s own
                    // L1->L2 redirect shares. Charging first and only conditionally refreshing
                    // would burn the player's stone AND set buildingLevel=2 with no visible
                    // effect, permanently - even leaving and re-entering doesn't self-heal it,
                    // since that redirect has the identical gap. No-charge-no-change on failure,
                    // same pattern promptRerollShopType() above already uses for "nothing this
                    // could become."
                    String l2Name = shopActor.getShopData().name + "L2";
                    ShopData l2Data = null;
                    for (ShopData candidate : new Array.ArrayIterator<>(WorldData.getShopList())) {
                        if (l2Name.equals(candidate.name)) {
                            l2Data = candidate;
                            break;
                        }
                    }
                    if (l2Data == null) {
                        GameHUD.getInstance().addNotification(
                                "[RED]This Armory has no Level 2 stock configured yet - upgrade unavailable.", true);
                        return;
                    }
                    EconomyBuildings.payCost(0, 0, EconomyBuildings.ARMORY_UPGRADE_STONE, 0);
                    changes.setBuildingLevel(shopActor.getObjectId(), 2);
                    guardsButton.setVisible(true);
                    upgradeButton.setVisible(false);
                    // Refresh the ALREADY-OPEN screen to the L2 item pool immediately (2026-08-13
                    // fix - user report: upgrading flipped the buttons but the item grid stayed at
                    // 6 items until leaving and re-entering the town, since MapStage.loadMap()'s own
                    // L1->L2 shop-name redirect only runs on a full map rebuild, never on this
                    // dialog's own confirm). Mirrors promptRerollShopType()/promptRerollArmory()
                    // above - swap the already-resolved L2 ShopData onto the actor, regenerate
                    // rewards with the shop's own weekly-refreshing seed (Armory is noRestock -
                    // same seed selection MapStage.loadMap() itself uses), and redraw.
                    shopActor.setShopData(l2Data);
                    clearGenerated();
                    Array<Reward> ret = new Array<>();
                    long shopSeed = changes.getWeeklyShopSeed(shopActor.getObjectId(),
                            WorldSave.getCurrentSave().getWorld().getCurrentDay());
                    WorldSave.getCurrentSave().getWorld().getRandom().setSeed(shopSeed);
                    for (RewardData rdata : EditionProgression.restrictShopRewardsForCurrentTown(
                            new Array.ArrayIterator<>(l2Data.rewards), changes, l2Data.name, "armory-upgrade")) {
                        ret.addAll(rdata.generate(false, false));
                    }
                    EconomyBuildings.injectGuaranteedTorchIfOwed(ret, l2Data, changes);
                    shopActor.setRewardData(ret);
                    loadRewards(ret, RewardScene.Type.Shop, shopActor);
                }, this::removeDialog));
    }

    /** Re-roll Inventory (2026-08-11, round 7; briefly an escalating-cost redesign on 2026-08-14,
     *  reverted 2026-08-15 back to a flat cost + hard once-per-7-days cooldown - see the cooldown
     *  check's own comment below) - deliberately does NOT go through shopActor.canRestock()/
     *  getRestockPrice() (the ordinary paid-restock path Armory is blocked from as a noRestock
     *  shop); PointOfInterestChanges.canManuallyRerollShop()/manuallyRerollShop() gate this
     *  instead, independent of the automatic weekly refresh. Rebuilds the displayed inventory the
     *  same way restockShop() does once the reroll is paid for. */
    private void promptRerollArmory() {
        if (shopActor == null || changes == null)
            return;
        // Defense-in-depth (2026-08-13, AI-town gate) - rerollButton is already hidden at AI
        // towns (only reachable via armoryFeatures' playerOwnedTown check). This was the one
        // action confirmed LIVE and fully functional (not merely visible) at the 5 AI capitals
        // before today - no level gate, no other restriction stopped it from actually working.
        if (!TownRestoration.isCurrentTownPlayerOwned(changes))
            return;
        int day = WorldSave.getCurrentSave().getWorld().getCurrentDay();
        // Flat cost, hard once-per-7-days cooldown (2026-08-15 user correction, reverting the
        // 2026-08-14 escalating-surcharge/no-cooldown redesign back to the original #33 spec -
        // "The Armory can only have it's inventory re-set once a week... Back to what it was
        // before"). See PointOfInterestChanges.canManuallyRerollShop().
        if (!changes.canManuallyRerollShop(shopActor.getObjectId(), day))
            return;
        int cost = EconomyBuildings.scaledCost(EconomyBuildings.ARMORY_REROLL_SHARD_COST);
        if (AdventurePlayer.current().getShards() < cost)
            return;
        showDialog(createGenericDialog("", "Re-roll this Armory's inventory for " + cost
                        + " [+Shards]?\nSeparate from the automatic weekly refresh.",
                Forge.getLocalizer().getMessage("lblYes"), Forge.getLocalizer().getMessage("lblNo"), () -> {
                    removeDialog();
                    AdventurePlayer.current().takeShards(cost);
                    changes.manuallyRerollShop(shopActor.getObjectId(), day);

                    HapticEngine.vibrate(FPref.UI_VIBRATE_ON_SHOP_ACTION, 5);
                    SoundSystem.instance.play(SoundEffectType.Shuffle, false);

                    clearGenerated();
                    ShopData data = shopActor.getShopData();
                    Array<Reward> ret = new Array<>();
                    long shopSeed = changes.getShopSeed(shopActor.getObjectId());
                    WorldSave.getCurrentSave().getWorld().getRandom().setSeed(shopSeed);
                    for (RewardData rdata : EditionProgression.restrictShopRewardsForCurrentTown(
                            new Array.ArrayIterator<>(data.rewards), changes, data.name, "armory-reroll")) {
                        ret.addAll(rdata.generate(false, false));
                    }
                    EconomyBuildings.injectGuaranteedTorchIfOwed(ret, data, changes);
                    shopActor.setRewardData(ret);
                    loadRewards(ret, RewardScene.Type.Shop, shopActor);
                    refreshRerollButton();
                }, this::removeDialog));
    }

    /** Refreshes rerollButton's text and disabled state - flat cost, gated on BOTH affordability
     *  AND the once-per-7-days cooldown (2026-08-15, reverted back from the brief 2026-08-14
     *  escalating-surcharge/no-cooldown redesign) - called after load and after a successful
     *  reroll. */
    private void refreshRerollButton() {
        if (shopActor == null || changes == null || !rerollButton.isVisible())
            return;
        int day = WorldSave.getCurrentSave().getWorld().getCurrentDay();
        int cost = EconomyBuildings.scaledCost(EconomyBuildings.ARMORY_REROLL_SHARD_COST);
        rerollButton.setText("[%80]Re-roll Inventory (" + cost + " [+Shards])");
        boolean canAfford = AdventurePlayer.current().getShards() >= cost;
        boolean offCooldown = changes.canManuallyRerollShop(shopActor.getObjectId(), day);
        rerollButton.setDisabled(!canAfford || !offCooldown);
    }

    private void promptDestroyShop() {
        if (shopActor == null || !shopActor.isDestroyable())
            return;
        showDialog(createGenericDialog("", "Destroy this building?\nYou will not get any resources back.",
                Forge.getLocalizer().getMessage("lblYes"), Forge.getLocalizer().getMessage("lblNo"), () -> {
                    removeDialog();
                    EconomyBuildings.destroyShopFromRewardScene(shopActor);
                    done(true);
                }, this::removeDialog));
    }

    // Armory shops (Capitol's ArmoryCommon/Uncommon/Rare/Mythic tiers, and the 5 AI capitals'
    // *Equipment/*Items shops) and the Capitol's 6 fixed land shops both restock via a weekly
    // reseed instead of the ordinary paid restock button (user request 2026-08-11: "add a note on
    // the Armory screen informing the player it will restock/new inventory weekly" - extended the
    // same day to land shops, which share the identical noRestock mechanism). Appended straight
    // into the shop header's gradient text rather than a new label, since that's the only
    // shop-name display RewardScene already has.
    private String armoryRestockNote() {
        if (shopActor == null)
            return "";
        // Keys off the actor's own weekly-refresh flag (2026-08-15, set by MapStage from the
        // tmx's noRestock property) - the earlier !canRestock() inference broke once the widened
        // ordinary card shops got their restock button back while KEEPING the weekly auto-reseed
        // (both facts true at once), and the original name-pattern check (isArmoryShop()||
        // isLandShop()) never covered the widened shops at all.
        if (!shopActor.isWeeklyRefresh())
            return "";
        return "\n[%50]Inventory will refresh weekly"; // user's exact wording, 2026-08-11
    }

    @Override
    public void connected(Controller controller) {
        super.connected(controller);
        updateDetailButton();
    }

    @Override
    public void disconnected(Controller controller) {
        super.disconnected(controller);
        updateDetailButton();
    }

    private void updateDetailButton() {
        detailButton.setVisible(Controllers.getCurrent() != null);
        detailButton.layout();
    }

    private void toggleToolTip() {
        Selectable selectable = getSelected();
        if (selectable == null)
            return;
        RewardActor actor;
        if (selectable.actor instanceof BuyButton) {
            actor = ((BuyButton) selectable.actor).rewardActor;
        } else if (selectable.actor instanceof RewardActor) {
            actor = (RewardActor) selectable.actor;
        } else {
            return;
        }
        if (!actor.isFlipped())
            performTouch(actor);

    }

    boolean doneClicked = false, shown = false;
    float flipCountDown = 1.0f;
    float exitCountDown = 0.0f; //Serves as additional check for when scene is exiting, so you can't double tap too fast.

    public void quitScene() {
        //There were reports of memory leaks after using the shop many times, so remove() everything on exit to be sure.
        for (Actor A : new Array.ArrayIterator<>(generated)) {
            if (A instanceof RewardActor) {
                ((RewardActor) A).removeTooltip();
                ((RewardActor) A).dispose();
                A.remove();
            }
        }
        //save RAM
        ImageCache.getInstance().unloadCardTextures(true);
        Forge.advFreezePlayerControls = false;
        if (this.collectionPool != null) {
            this.collectionPool.clear();
            this.collectionPool = null;
        }
        Forge.switchToLast();
    }

    public void reactivateInputs() {
        Gdx.input.setInputProcessor(stage);
        doneButton.toFront();
    }

    public boolean done() {
        return done(false);
    }

    boolean done(boolean skipShowLoot) {
        GameHUD.getInstance().getTouchpad().setVisible(false);
        if (!skipShowLoot) {
            showLootOrDone();
            return true;
        }
        if (type != null) {
            switch (type) {
                case Shop:
                case QuestReward:
                case Loot:
                    break;
            }
        }
        shown = false;
        clearGenerated();
        quitScene();
        return true;
    }

    void clearGenerated() {
        for (Actor actor : new Array.ArrayIterator<>(generated)) {
            if (!(actor instanceof RewardActor)) {
                continue;
            }
            RewardActor reward = (RewardActor) actor;
            if (type == Type.Loot)
                AdventurePlayer.current().addReward(reward.getReward());
            if (type == Type.QuestReward)
                AdventurePlayer.current().addReward(reward.getReward()); // TODO Want to customize this soon to have selectable rewards which will be handled different here
            reward.clearHoldToolTip();
            try {
                stage.getActors().removeValue(reward, true);
            } catch (Exception e) {
            }
        }
    }

    public List<RewardActor> getGeneratedRewards() {
        List<RewardActor> rewards = new ArrayList<>();
        for (Actor actor : new Array.ArrayIterator<>(generated)) {
            if (!(actor instanceof RewardActor)) {
                continue;
            }
            RewardActor reward = (RewardActor) actor;
            if (!reward.frontSideUp())
                continue;
            rewards.add(reward);
        }
        return rewards;
    }

    @Override
    public void act(float delta) {
        stage.act(delta);
        ImageCache.getInstance().allowSingleLoad();
        if (doneClicked) {
            if (type == Type.Loot || type == Type.QuestReward) {
                flipCountDown -= Gdx.graphics.getDeltaTime();
                exitCountDown += Gdx.graphics.getDeltaTime();
            }
            if (flipCountDown <= 0) {
                clearGenerated();
                quitScene();
            }
        }
    }

    private boolean pendingEmptyBoosterNote = false;

    @Override
    public void enter() {
        autoSell = false;
        updateDetailButton();
        super.enter();
        if (pendingEmptyBoosterNote) {
            pendingEmptyBoosterNote = false;
            showDialog(createGenericDialog("", "No boosters available yet!\nResearch more expansions"
                    + " at the Research Lab to stock this shop.",
                    Forge.getLocalizer().getMessage("lblOK"), null, this::removeDialog, null));
        }
    }

    private void showLootOrDone() {
        boolean exit = true;
        for (Actor actor : new Array.ArrayIterator<>(generated)) {
            if (!(actor instanceof RewardActor)) {
                continue;
            }
            RewardActor reward = (RewardActor) actor;
            if (!reward.isFlipped()) {
                exit = false;
                break;
            }
        }
        if (exit)
            done(true);
        else if ((type == Type.Loot || type == Type.QuestReward) && !shown) {
            shown = true;
            float delay = 0.09f;
            generated.shuffle();
            for (Actor actor : new Array.ArrayIterator<>(generated)) {
                if (!(actor instanceof RewardActor)) {
                    continue;
                }
                RewardActor reward = (RewardActor) actor;
                if (!reward.isFlipped()) {
                    Timer.schedule(new Timer.Task() {
                        @Override
                        public void run() {
                            reward.flip();
                        }
                    }, delay);
                    delay += 0.12f;
                }
            }
        } else {
            done(true);
        }
    }

    /** Weekly-escalating surcharge added on top of a restock's flat rarity-tier price (2026-08-15
     *  user spec, moved here from the Card Shop Type/Armory reroll buttons - "the One below that,
     *  that re-rolls the cards available in the shop" is what should carry the +1/week cost, same
     *  schedule as Guard pay: day 7/14/21). 0 when changes is null (a shop with no persisted
     *  PointOfInterestChanges can't track a surcharge). */
    /** Round 106: Ring City shops restock at ringShopRestockMultiplier (3) x a normal shop's price, multiplied again by every restock this week. */
    private int restockPriceNow() {
        if (EconomyBuildings.isRingShop(shopActor.getShopData())) {
            float mult = Math.max(1f, forge.adventure.util.Config.instance().getTuningData().ringShopRestockMultiplier);
            double price = Math.max(2, shopActor.getRestockPrice()) * mult * Math.pow(mult, restockSurcharge());
            return (int) Math.min(Integer.MAX_VALUE / 2, Math.round(price));
        }
        return shopActor.getRestockPrice() + restockSurcharge();
    }
    private int restockSurcharge() {
        if (changes == null)
            return 0;
        int day = WorldSave.getCurrentSave().getWorld().getCurrentDay();
        return changes.rerollSurcharge(shopActor.getObjectId(), day);
    }

    void updateRestockButton() {
        if (!shopActor.canRestock())
            return;
        int price = restockPriceNow();
        restockButton.setText("[+Refresh][+shards]" + price);
        restockButton.setDisabled(WorldSave.getCurrentSave().getPlayer().getShards() < price);
    }

    void restockShop() {
        if (!shopActor.canRestock())
            return;
        // A booster shop that can't stock anything (no booster-capable unlocked edition yet)
        // would charge the shards, reseed, and render an empty shelf with no explanation -
        // refuse BEFORE any money or seed state changes (2026-08-12 review finding + user
        // request for an explanatory note).
        if (EconomyBuildings.isBoosterShop(shopActor.getShopData())
                && !EditionProgression.playerHasBoosterCapableUnlockedEdition()) {
            showDialog(createGenericDialog("", "No boosters available yet!\nResearch more expansions"
                    + " at the Research Lab to stock this shop.",
                    Forge.getLocalizer().getMessage("lblOK"), null, this::removeDialog, null));
            return;
        }
        int price = restockPriceNow();
        int day = WorldSave.getCurrentSave().getWorld().getCurrentDay();
        if (changes != null) {
            changes.generateNewShopSeed(shopActor.getObjectId());
            changes.recordReroll(shopActor.getObjectId(), day);
        }

        Current.player().takeShards(price);

        HapticEngine.vibrate(FPref.UI_VIBRATE_ON_SHOP_ACTION, 5);
        SoundSystem.instance.play(SoundEffectType.Shuffle, false);

        updateBuyButtons();
        if (changes == null)
            return;

        clearGenerated();

        ShopData data = shopActor.getShopData();
        Array<Reward> ret = new Array<>();

        long shopSeed = changes.getShopSeed(shopActor.getObjectId());
        WorldSave.getCurrentSave().getWorld().getRandom().setSeed(shopSeed);
        for (RewardData rdata : EditionProgression.restrictShopRewardsForCurrentTown(
                new Array.ArrayIterator<>(data.rewards), changes, data.name, "restock")) {
            ret.addAll(rdata.generate(false, false));
        }
        shopActor.setRewardData(ret);
        loadRewards(ret, RewardScene.Type.Shop, shopActor);
    }

    public void loadRewards(Deck deck, Type type, ShopActor shopActor, boolean noSell) {
        Array<Reward> rewards = new Array<>();
        for (PaperCard card : deck.getAllCardsInASinglePool(true, true).toFlatList()) {
            rewards.add(new Reward(card, noSell));
        }
        loadRewards(rewards, type, shopActor);
    }

    public void loadSelectableRewards(Array<Reward> choices, Type type, int countToSelect, float priceMultiplier) {
        if (type != Type.RewardChoice)
            return;
        this.remainingSelections = countToSelect;
        this.selectionPriceMultiplier = priceMultiplier;
        loadRewards(choices, type, null);
    }

    void updateCollectionPool() {
        if (Type.Shop != this.type)
            return;
        if (this.collectionPool == null)
            this.collectionPool = new ItemPool<>(PaperCard.class);
        else
            this.collectionPool.clear();

        this.collectionPool.addAllFlat(AdventurePlayer.current().getCollectionCards(true).toFlatList());
    }

    public void loadRewards(Array<Reward> newRewards, Type type, ShopActor shopActor) {
        // Booster shop with nothing to sell because no unlocked edition can make a booster yet
        // (fresh Insane save = Jumpstart only): explain instead of showing a bare empty shelf
        // (user request 2026-08-12). Deferred to enter() - this runs before the scene switch.
        if (type == Type.Shop && newRewards.isEmpty() && shopActor != null
                && EconomyBuildings.isBoosterShop(shopActor.getShopData())
                && !EditionProgression.playerHasBoosterCapableUnlockedEdition())
            pendingEmptyBoosterNote = true;
        // Merge Gold and Shards rewards into single entries
        int totalGold = 0;
        int totalShards = 0;
        Array<Reward> others = new Array<>();
        for (Reward r : new Array.ArrayIterator<>(newRewards)) {
            switch (r.getType()) {
                case Gold:
                    totalGold += r.getCount();
                    break;
                case Shards:
                    totalShards += r.getCount();
                    break;
                default:
                    others.add(r);
                    break;
            }
        }
        newRewards.clear();
        if (totalGold > 0) {
            newRewards.add(new Reward(Reward.Type.Gold, totalGold));
        }
        if (totalShards > 0) {
            newRewards.add(new Reward(Reward.Type.Shards, totalShards));
        }
        for (Reward r : others) {
            newRewards.add(r);
        }

        headerLabel.clearListeners();
        // Sort the rewards based on the rarity of the card inside the reward/ lets give items rarity
        newRewards.sort(Comparator.comparing(reward -> {
            if (reward.getCard() != null && reward.getCard().getRarity() != null) {
                return reward.getCard().getRarity().ordinal();
            }
            // Return a default value or handle the case where rarity is not present
            return Integer.MAX_VALUE; // Assuming higher values mean less priority in sorting
        }));
        clearSelectable();
        this.type = type;
        doneClicked = false;
        updateCollectionPool();
        destroyButton.setVisible(false); // re-enabled by the Shop case below when applicable
        guardsButton.setVisible(false); // re-enabled by the Shop case below when applicable
        upgradeButton.setVisible(false); // re-enabled by the Shop case below when applicable
        rerollButton.setVisible(false); // re-enabled by the Shop case below when applicable
        shopTypeRerollButton.setVisible(false); // re-enabled by the Shop case below when applicable
        buyBlueprintButton.setVisible(false);   // ditto
        if (type == Type.Shop) {
            this.shopActor = shopActor;
            this.changes = shopActor.getMapStage().getChanges();
            addToSelectable(restockButton);
        } else {
            doneButton.setText("[+OK]");
        }
        for (Actor actor : new Array.ArrayIterator<>(generated)) {
            actor.remove();
            if (actor instanceof RewardActor) {
                ((RewardActor) actor).dispose();
            }
        }
        addToSelectable(doneButton);
        generated.clear();

        Actor card = ui.findActor("cards");
        //reset pos
        headerLabel.setPosition(headerLabelOrigPos.x, headerLabelOrigPos.y);
        headerLabel.addListener(new ClickListener() {
            @Override
            public void clicked(InputEvent event, float x, float y) {
                if (type == Type.Loot || type == Type.QuestReward) {
                    autoSell = !autoSell;
                    String cb = autoSell ? "\u2611 " : "\u2610 ";
                    headerLabel.setText("[%?SHINY][;]" + cb + Forge.getLocalizer().getMessage("lblAll"));
                    for (Actor A : new Array.ArrayIterator<>(generated)) {
                        if (A instanceof RewardActor) {
                            ((RewardActor) A).setAutoSell(autoSell);
                        }
                    }
                }
            }
        });
        if (type == Type.Shop) {
            String shopName = shopActor.getDescription();
            if (shopName != null && !shopName.isEmpty()) {
                headerLabel.setVisible(true);
                headerLabel.setText("[%?SHINY]{GRADIENT}" + shopName + armoryRestockNote() + "{ENDGRADIENT}");
                headerLabel.skipToTheEnd();
            } else {
                headerLabel.setVisible(false);
            }
            Actor background = ui.findActor("market_background");
            if (background != null)
                background.setVisible(true);
        } else {
            headerLabel.setVisible(false);
            headerLabel.setText("");
            Actor background = ui.findActor("market_background");
            if (background != null)
                background.setVisible(false);
        }

        float targetWidth = card.getWidth();
        float targetHeight = card.getHeight();
        float xOff = card.getX();
        float yOff = card.getY();

        int numberOfRows = 0;
        float cardWidth = 0;
        float cardHeight = 0;
        float bestCardHeight = 0;
        int numberOfColumns = 0;
        float targetArea = targetHeight * targetWidth;
        float oldCardArea = 0;
        float newArea = 0;

        switch (type) {
            case Shop:
                String shopName = shopActor.getDescription();
                if ((shopName != null && !shopName.isEmpty())) {
                    headerLabel.setVisible(true);
                    headerLabel.setText("[%?SHINY]{GRADIENT}" + shopName + armoryRestockNote() + "{ENDGRADIENT}");
                    headerLabel.skipToTheEnd();
                }

                if (shopActor.canRestock()) {
                    restockButton.setVisible(true);
                } else {
                    restockButton.setVisible(false);
                    restockButton.setDisabled(true);
                }
                destroyButton.setVisible(shopActor.isDestroyable());
                if (destroyButton.isVisible())
                    addToSelectable(destroyButton);
                boolean isArmory = EconomyBuildings.isArmoryShop(shopActor.getShopData());
                // AI-town gate (2026-08-13, user spec: "only the player can build/upgrade
                // stuff... no AI towns/cities should be touched"). Every OTHER economy-building
                // action (Bank/Mines/Outlook/Teleporter/Archaeologist/guard hire/Destroy
                // Building) is already structurally unreachable at AI towns - they only open via
                // ShopActor's isDestroyed()/isWastelandTown() branch, which an AI town/capital
                // never satisfies (never wasteland). This Armory-family block below is the one
                // path that bypassed that gate entirely: the 5 AI capitals' colored
                // Equipment/Items shops match isArmoryShop() too, and nothing here checked WHO
                // owns the town - Re-roll Inventory/Re-roll Shop Type were live and paid-for-
                // real at any AI capital, and Upgrade Armory only failed harmlessly by the
                // unrelated coincidence that shops.json has no *L2 entry for those shop names.
                boolean playerOwnedTown = TownRestoration.isCurrentTownPlayerOwned(changes);
                // Stock planes (Shandalar) have shops named *Equipment/*Items that match
                // isArmoryShop, and multi-name shop lists that qualify for the type re-roll -
                // both are mod features and must stay plane-opt-in (CLAUDE.md ground rule;
                // 2026-08-12 review finding). isArmory alone still matters below for excluding
                // Armories from the type re-roll on ANY plane.
                boolean armoryFeatures = isArmory && Config.instance().getConfigData().armoryGuardsEnabled && playerOwnedTown;
                int armoryLevel = changes.getBuildingLevel(shopActor.getObjectId());
                guardsButton.setVisible(armoryFeatures && armoryLevel >= 2);
                if (guardsButton.isVisible())
                    addToSelectable(guardsButton);
                upgradeButton.setVisible(armoryFeatures && armoryLevel < 2);
                if (upgradeButton.isVisible()) {
                    // Text refreshed here too (round 4, difficulty price multiplier) - was
                    // previously baked in once from the raw constant at construction.
                    upgradeButton.setText("[%80]Upgrade Armory (" + EconomyBuildings.costLabel(0, 0, EconomyBuildings.ARMORY_UPGRADE_STONE, 0) + ")");
                    // Greyed-out-when-unaffordable (2026-08-13 fix, user report) - this button
                    // was visible but never disabled when unaffordable, relying solely on
                    // promptUpgradeArmory()'s own click-handler check silently no-oping. Every
                    // other cost-gated button (restockButton/rerollButton/BuyButton/
                    // EconomyBuildings' addButtonRow/addHalfButton/buildTradeRow) already does
                    // this - matches that established pattern.
                    upgradeButton.setDisabled(!EconomyBuildings.canAffordCost(0, 0, EconomyBuildings.ARMORY_UPGRADE_STONE, 0));
                    addToSelectable(upgradeButton);
                }
                // Re-roll Inventory (round 7) - Armory-only again (2026-08-15 user correction:
                // the widened card shops get the small restock button with the weekly-escalating
                // surcharge instead, NOT this high-cost hard-cooldown button - it was briefly
                // extended to them earlier the same day and reverted within hours), any level,
                // independent of the guards/upgrade level gate above.
                rerollButton.setVisible(armoryFeatures);
                if (rerollButton.isVisible()) {
                    // Text and disabled-state both come from refreshRerollButton() now - flat cost,
                    // gated on affordability AND the once-per-7-days cooldown (no surcharge concept
                    // for Armory; that lives only on the ordinary-shop restock button instead).
                    refreshRerollButton();
                    addToSelectable(rerollButton);
                }
                // Shop Type Re-Roll (round 8) - ordinary card shops only, mutually exclusive with
                // Armory's own rerollButton above (a shop resolves to exactly one ShopData at a
                // time, so they share a row position safely).
                shopTypeRerollButton.setVisible(Config.instance().getConfigData().shopTypeRerollEnabled
                        && !isArmory && playerOwnedTown && shopActor.getMapStage().isShopTypeRerollable(shopActor.getObjectId()));
                if (shopTypeRerollButton.isVisible()) {
                    // No flat price on the button any more (2026-08-30): the chooser prices per
                    // tier and nets off the re-type credit, so a single number here would be
                    // wrong for most picks. Individual entries inside the chooser carry their own
                    // price and their own affordability greying, so the button itself is always
                    // enabled - the gate moved one level in.
                    shopTypeRerollButton.setText("[%80]Re-assign Shop Type");
                    shopTypeRerollButton.setDisabled(false);
                    addToSelectable(shopTypeRerollButton);
                }
                // Buy Blueprint (2026-08-30) - deliberately NOT gated on playerOwnedTown, unlike
                // every other button here: learning a type by visiting a RIVAL town is the whole
                // point. Only shown for an ordinary card shop whose type is still unknown, and
                // only when the slot is a real multi-type card-shop slot (isShopTypeRerollable
                // excludes the Armory and the fixed Capitol land shops).
                boolean blueprintOffered = Config.instance().getConfigData().shopBlueprintsEnabled
                        && !isArmory
                        && shopActor.getShopData() != null
                        // The 5 Cartographer basic-land shops are outside the blueprint system
                        // (user spec 2026-08-31) - the player Capitol's copies unlock by visiting
                        // an AI capital, so a blueprint for one buys nothing.
                        && !EconomyBuildings.isBasicLandShop(shopActor.getShopData())
                        && shopActor.getMapStage().isShopTypeRerollable(shopActor.getObjectId())
                        && !EconomyBuildings.isShopTypeUnlocked(shopActor.getShopData().name);
                buyBlueprintButton.setVisible(blueprintOffered);
                if (blueprintOffered) {
                    String tier = EconomyBuildings.shopTierOf(shopActor.getMapStage(),
                            shopActor.getObjectId(), shopActor.getShopData().name);
                    // Standing-scaled price (user spec 2026-08-31) - Partner 30% off, Happy 15%,
                    // reusing the same table card prices already use in a color's town.
                    int cost = EconomyBuildings.blueprintShardCostHere(tier);
                    // Shown-but-greyed when this color will not deal with you at this tier, so
                    // the reputation ladder is visible rather than a mysteriously absent button.
                    // The button stays ENABLED when merely unaffordable - promptBuyBlueprint()
                    // explains the shortfall, which is more use than a dead control.
                    String block = EconomyBuildings.blueprintStandingBlock(tier);
                    buyBlueprintButton.setText(block == null
                            ? "[%80]Buy Blueprint (" + cost + " [+Shards])"
                            : "[%80][GRAY]Buy Blueprint (standing too low)");
                    buyBlueprintButton.setDisabled(block != null);
                    addToSelectable(buyBlueprintButton);
                }
                break;
            case QuestReward:
            case Loot:
                headerLabel.setPosition(restockButton.getX(), restockButton.getY());
                headerLabel.setVisible(true);
                headerLabel.setText("[%?SHINY][;]\u2610 " + Forge.getLocalizer().getMessage("lblAll"));
                headerLabel.skipToTheEnd();
                restockButton.setVisible(false);
                break;
            case RewardChoice:
                restockButton.setVisible(false);
                headerLabel.setVisible(remainingSelections > 0);
                headerLabel.setText(Forge.getLocalizer().getMessage("lblSelectRewards", remainingSelections));
                // PRICED picks (Thief Merchant, selectionPriceMultiplier > 0) must leave Done
                // enabled from the start (2026-08-26 review finding, soft-lock): this screen has
                // no other exit, and every pick button is gold-gated - a player who couldn't
                // afford any of the 8 cards was permanently stuck (kill-the-process territory),
                // and one who could was forced to buy to leave. A merchant is something you can
                // walk away from. FREE picks (quest-authored grantRewardsChoice) keep the
                // original mandatory-pick contract unchanged.
                doneButton.setDisabled(remainingSelections > 0 && selectionPriceMultiplier <= 0f);
        }
        for (int h = 1; h < targetHeight; h++) {
            cardHeight = h;
            if (type == Type.Shop || type == Type.RewardChoice) {
                cardHeight += doneButton.getHeight();
            }
            //cardHeight=targetHeight/i;
            cardWidth = h / CARD_WIDTH_TO_HEIGHT;
            newArea = newRewards.size * cardWidth * cardHeight;

            int rows = (int) (targetHeight / cardHeight);
            int cols = (int) Math.ceil(newRewards.size / (double) rows);
            if (newArea > oldCardArea && newArea <= targetArea && rows * cardHeight < targetHeight && cols * cardWidth < targetWidth) {
                oldCardArea = newArea;
                numberOfRows = rows;
                numberOfColumns = cols;
                bestCardHeight = h;
            }
        }
        float AR = 480f / 270f;
        int x = Forge.getDeviceAdapter().getRealScreenSize(false).getLeft();
        int y = Forge.getDeviceAdapter().getRealScreenSize(false).getRight();
        int realX = Forge.getDeviceAdapter().getRealScreenSize(true).getLeft();
        int realY = Forge.getDeviceAdapter().getRealScreenSize(true).getRight();
        float fW = Math.max(x, y);
        float fH = Math.min(x, y);
        float mul = fW / fH < AR ? AR / (fW / fH) : (fW / fH) / AR;
        if (fW / fH >= 2f) {//tall display
            mul = (fW / fH) - ((fW / fH) / AR);
            if ((fW / fH) >= 2.1f && (fW / fH) < 2.2f)
                mul *= 0.9f;
            else if ((fW / fH) > 2.2f) //ultrawide 21:9 Galaxy Fold, Huawei X2, Xperia 1
                mul *= 0.8f;
        }
        cardHeight = bestCardHeight * 0.90f;
        Float custom = Forge.isLandscapeMode()
            ? Config.instance().getSettingData().rewardCardAdjLandscape
            : Config.instance().getSettingData().rewardCardAdj;
        if (custom != null && custom != 1f) {
            mul *= custom;
        } else {
            if (realX > x || realY > y) {
                mul *= Forge.isLandscapeMode() ? 0.95f : 1.05f;
            } else {
                //immersive | no navigation and/or showing cutout cam
                if (fW / fH > 2.2f)
                    mul *= Forge.isLandscapeMode() ? 1.1f : 1.6f;
                else if (fW / fH >= 2.1f)
                    mul *= Forge.isLandscapeMode() ? 1.05f : 1.5f;
                else if (fW / fH >= 2f)
                    mul *= Forge.isLandscapeMode() ? 1f : 1.4f;
            }
        }
        cardWidth = (cardHeight / CARD_WIDTH_TO_HEIGHT) * mul;

        yOff += (targetHeight - (cardHeight * numberOfRows)) / 2f;
        xOff += (targetWidth - (cardWidth * numberOfColumns)) / 2f;

        float spacing = 2;
        int i = 0;
        for (Reward reward : new Array.ArrayIterator<>(newRewards)) {
            boolean skipCard = false;
            if (type == Type.Shop) {
                if (changes.wasCardBought(shopActor.getObjectId(), i)) {
                    skipCard = true;
                }
            }

            int currentRow = (i / numberOfColumns);
            float lastRowXAdjust = 0;
            if (currentRow == numberOfRows - 1) {
                int lastRowCount = newRewards.size % numberOfColumns;
                if (lastRowCount != 0)
                    lastRowXAdjust = ((numberOfColumns * cardWidth) - (lastRowCount * cardWidth)) / 2;
            }

            RewardActor actor = new RewardActor(reward, type == Type.Loot || type == Type.QuestReward, type, type == Type.Shop && (numberOfRows > 2 || numberOfColumns > 2));

            actor.setBounds(lastRowXAdjust + xOff + cardWidth * (i % numberOfColumns) + spacing, yOff + cardHeight * currentRow + spacing, cardWidth - spacing * 2, cardHeight - spacing * 2);

            if (type == Type.Shop) {
                if (currentRow != ((i + 1) / numberOfColumns))
                    yOff += doneButton.getHeight();

                BuyButton buyCardButton = new BuyButton(shopActor.getObjectId(), i, actor, reward, doneButton, shopActor.getPriceModifier());
                generated.add(buyCardButton);
                if (!skipCard) {
                    stage.addActor(buyCardButton);
                    addToSelectable(buyCardButton);
                }
            } else if (type == Type.RewardChoice) {
                if (currentRow != ((i + 1) / numberOfColumns))
                    yOff += doneButton.getHeight();
                ChooseRewardButton chooseRewardButton = new ChooseRewardButton(i, actor, reward, doneButton);
                generated.add(chooseRewardButton);
                stage.addActor(chooseRewardButton);
                addToSelectable(chooseRewardButton);
            } else {
                addToSelectable(actor);
            }
            generated.add(actor);
            if (!skipCard) {
                stage.addActor(actor);
            }
            i++;
        }
        if (type == Type.Shop) {
            updateBuyButtons();
            updateRestockButton();
        } else if (type == Type.RewardChoice) {
            // Priced RewardChoice (Thief Merchant chest event) initial affordability state
            // (2026-08-29 user report): ChooseRewardButton.update() has the correct
            // setDisabled(gold < price) check, but nothing called it until AFTER the first
            // successful purchase (to refresh the OTHERS) - so on screen open every tile
            // defaulted to enabled regardless of price. The purchase itself was always safely
            // gated (the click handler re-checks gold before spending), but an unaffordable
            // tile looked clickable and silently did nothing when tapped. Mirrors the Shop
            // case's updateBuyButtons() call immediately above.
            updateChooseRewardButtons();
        }
    }

    private void updateBuyButtons() {
        for (Actor actor : new Array.ArrayIterator<>(generated)) {
            if (actor instanceof BuyButton) {
                ((BuyButton) actor).update();
            }
        }
    }

    private void updateChooseRewardButtons() {
        for (Actor actor : new Array.ArrayIterator<>(generated)) {
            if (actor instanceof ChooseRewardButton) {
                ((ChooseRewardButton) actor).update();
            }
        }
    }

    private class BuyButton extends TextraButton {
        private final int objectID;
        private final int index;
        public RewardActor rewardActor;
        private Reward reward;
        int price;
        boolean isSold;

        void update() {
            setDisabled(WorldSave.getCurrentSave().getPlayer().getGold() < price);
            if (isSold)
                setText("SOLD");
            else
                updateOwned();
        }

        void updateOwned() {
            if (Type.Shop != type)
                return;
            if (collectionPool != null && Reward.Type.Card.equals(reward.getType()))
                setText("[%75][+GoldCoin] " + price + "\n" + Forge.getLocalizer().getMessage("lblOwned") + ": " + collectionPool.count(reward.getCard()));
            else if (Reward.Type.Item.equals(reward.getType()))
                setText("[%75][+GoldCoin] " + price + "\n" + Forge.getLocalizer().getMessage("lblOwned") + ": " + AdventurePlayer.current().countItem(reward.getItem().name));
        }

        public BuyButton(int id, int i, RewardActor actor, Reward reward, TextraButton style, float shopModifier) {
            super("", style.getStyle(), Controls.getTextraFont());
            this.objectID = id;
            this.index = i;
            rewardActor = actor;
            this.reward = reward;
            setHeight(style.getHeight());
            setWidth(actor.getWidth());
            setX(actor.getX());
            setY(actor.getY() - getHeight());
            price = CardUtil.getRewardPrice(actor.getReward());
            price *= Current.player().goldModifier();
            price *= shopModifier;
            setText("[+GoldCoin] " + price);
            updateOwned();
            addListener(new ClickListener() {
                @Override
                public void clicked(InputEvent event, float x, float y) {
                    if (Current.player().getGold() >= price) {
                        if (!shopActor.isUnlimited())
                            changes.buyCard(objectID, index);

                        Current.player().takeGold(price);
                        Current.player().addReward(rewardActor.getReward());

                        // Guaranteed-Torch fulfillment (2026-08-14 redesign - see EconomyBuildings.
                        // injectGuaranteedTorchIfOwed()'s own comment): the moment the player buys
                        // ANY Torch while the guarantee is still unfulfilled, it's done - whether
                        // this was literally the injected guaranteed slot or a separately-rolled
                        // one doesn't matter, they now own a Torch either way. Stops the injection
                        // from re-firing on every future stock regeneration.
                        if (Reward.Type.Item.equals(rewardActor.getReward().getType())
                                && "Torch".equals(rewardActor.getReward().getItem().name)
                                && !AdventurePlayer.current().checkCharacterFlag("firstArmoryTorchGranted")) {
                            AdventurePlayer.current().setCharacterFlag("firstArmoryTorchGranted", 1);
                            System.out.println("[TFR-FirstArmoryTorch] guarantee fulfilled - player bought a Torch");
                        }

                        HapticEngine.vibrate(FPref.UI_VIBRATE_ON_SHOP_ACTION, 5);
                        SoundSystem.instance.play(SoundEffectType.FlipCoin, false);

                        if (changes == null)
                            return;
                        isSold = true;
                        setDisabled(true);
                        rewardActor.sold();
                        getColor().a = 0.5f;
                        updateCollectionPool();
                        updateBuyButtons();
                        removeListener(this);
                    }
                }
            });
        }
    }

    private class ChooseRewardButton extends TextraButton {
        private final int index;
        public RewardActor rewardActor;
        private Reward reward;
        int price;
        boolean isSold;

        void update() {
            setDisabled(remainingSelections <= 0 || Current.player().getGold() < price);
            if (isSold)
                setText("SELECTED");
            else
                updateOwned();
        }

        void updateOwned() {
            String label = price > 0 ? "[%75][+GoldCoin] " + price : "Pick Reward";
            if (Type.Shop != type) {
                setText(label);
                return;
            }
            if (collectionPool != null && Reward.Type.Card.equals(reward.getType()))
                setText(label + "\n" + Forge.getLocalizer().getMessage("lblOwned") + ": " + collectionPool.count(reward.getCard()));
            else if (Reward.Type.Item.equals(reward.getType()))
                setText(label + "\n" + Forge.getLocalizer().getMessage("lblOwned") + ": " + AdventurePlayer.current().countItem(reward.getItem().name));
        }

        public ChooseRewardButton(int i, RewardActor actor, Reward reward, TextraButton style) {
            super("", style.getStyle(), Controls.getTextraFont());
            this.index = i;
            rewardActor = actor;
            this.reward = reward;
            setHeight(style.getHeight());
            setWidth(actor.getWidth());
            setX(actor.getX());
            setY(actor.getY() - getHeight());

            // Priced RewardChoice (Chest's Thief Merchant) vs. the original free pick (quest
            // grantRewardsChoice) - see selectionPriceMultiplier's own comment.
            price = selectionPriceMultiplier > 0f
                    ? Math.round(CardUtil.getRewardPrice(reward) * selectionPriceMultiplier)
                    : 0;
            setText(price > 0 ? "[%75][+GoldCoin] " + price : "Pick Reward");
            updateOwned();
            addListener(new ClickListener() {
                @Override
                public void clicked(InputEvent event, float x, float y) {
                    if (remainingSelections >= 1 && Current.player().getGold() >= price) {

                        remainingSelections--;
                        if (price > 0)
                            Current.player().takeGold(price);
                        Current.player().addReward(rewardActor.getReward());

                        headerLabel.setVisible(remainingSelections > 0);
                        headerLabel.setText("Select " + remainingSelections + " rewards");
                        // Same priced-picks-are-optional rule as the build-time check - see the
                        // RewardChoice case in loadRewards() (soft-lock fix).
                        doneButton.setDisabled(remainingSelections > 0 && selectionPriceMultiplier <= 0f);

                        HapticEngine.vibrate(FPref.UI_VIBRATE_ON_ADVENTURE_REWARD, 5);
                        //SoundSystem.instance.play(SoundEffectType.FlipCoin, false);

                        isSold = true;
                        setDisabled(true);
                        rewardActor.sold();
                        getColor().a = 0.5f;
                        updateCollectionPool();
                        updateChooseRewardButtons();
                        removeListener(this);
                    }
                }
            });
        }
    }
}
