package forge.adventure.character;

import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.utils.Array;
import forge.Forge;
import forge.adventure.data.ShopData;
import forge.adventure.pointofintrest.PointOfInterest;
import forge.adventure.pointofintrest.PointOfInterestChanges;
import forge.adventure.scene.RewardScene;
import forge.adventure.scene.TileMapScene;
import forge.adventure.stage.MapStage;
import forge.adventure.util.ColorReputation;
import forge.adventure.util.Config;
import forge.adventure.util.EconomyBuildings;
import forge.adventure.util.MapDialog;
import forge.adventure.util.Reward;
import forge.adventure.util.TownRestoration;
import forge.adventure.world.WorldSave;


/**
 * Map actor that will open the Shop on collision
 */
public class ShopActor extends MapActor {
    private final MapStage stage;
    private ShopData shopData;
    Array<Reward> rewardData;
    // Capitol land shops (user spec 2026-08-09): the 6 bottom-right shops in player_capital.tmx
    // (the 5 basic-land colors + Land) are marked "fixedShop" in the tmx. They never randomize,
    // always repair as exactly what they are (no Bank/Mine conversion menu), and once repaired
    // draw NO overlay icon - their hut art is already baked into the map's own tile layers.
    private boolean fixedShop = false;
    // Teleporter's portal animation needs its own elapsed-time clock (the 4-frame "Active"
    // shimmer, see EconomyBuildings.getTeleporterActiveAnimation()) - Animation.getKeyFrame()
    // takes elapsed time, not a frame index, and nothing else on this actor already tracks time.
    private float teleporterAnimTime = 0f;

    public ShopActor(MapStage stage, int id, Array<Reward> rewardData, ShopData data) {
        super(id);
        this.stage = stage;
        this.shopData = data;
        this.rewardData = rewardData;
    }

    @Override
    public void act(float delta) {
        super.act(delta);
        // Hide the real building art baked into the town's tile layers whenever this shop's own
        // overlay art (draw(), below) is meant to fully replace it - see MapStage's
        // findOverheadTiles()/setShopOverheadTilesHidden() for why this is necessary at all
        // (there's no way to hide a baked tile via the shop object itself).
        int economyType = EconomyBuildings.getBuildingType(stage.getChanges(), objectId);
        stage.setShopOverheadTilesHidden(objectId, isDestroyed() || economyType != EconomyBuildings.NONE);
        teleporterAnimTime += delta;
    }

    public float getPriceModifier() {
        PointOfInterestChanges changes = stage.getChanges();
        float townPricemodifier = changes == null ? 1f : changes.getTownPriceModifier();
        float shopPriceModifier = changes == null ? 1f : changes.getShopPriceModifier(objectId);
        float ringPrice = EconomyBuildings.isRingShop(shopData) ? Math.max(0.1f, Config.instance().getTuningData().ringShopPriceMultiplier) : 1f; // round 106
        return shopPriceModifier * townPricemodifier * colorReputationModifier() * ownershipBaseModifier(changes) * ringPrice;
    }

    // Ownership base price adjustment (2026-08-17 user spec: "cards bought at AI shops 25% more
    // expensive... 25% cheaper at player shops... before any other discounts/increases like
    // reputation, relations, etc" - to push the player toward building their own shops and
    // researching set unlocks instead of just buying everywhere). A fourth multiplicative factor
    // alongside the three above - composes on top, doesn't replace any of them. Player-owned
    // covers a restored town OR the Capitol (isCurrentTownPlayerOwned(), not the narrower
    // isTownRestored() colorReputationModifier() uses above - that one gets away with skipping
    // the Capitol case only because colorOfTown() already returns null for it too, which would
    // have silently left the Capitol's own shops out of the player discount here). Neutral towns
    // (Spawn) get neither adjustment, same as colorReputationModifier()'s own neutral case.
    private float ownershipBaseModifier(PointOfInterestChanges changes) {
        if (TownRestoration.isCurrentTownPlayerOwned(changes))
            return Config.instance().getTuningData().playerShopPriceMultiplier;
        PointOfInterest point = TileMapScene.instance().rootPoint;
        if (point != null && ColorReputation.colorOfTown(point.getData()) != null)
            return Config.instance().getTuningData().aiShopPriceMultiplier;
        return 1f;
    }

    // Color reputation (MOD_SCOPE.md #1) consequence: card prices in a color's town scale with
    // the player's standing with that color (Partner 30% off ... War 25% up). Stacks
    // multiplicatively with the existing per-town haggling reputation above - deliberate, they
    // measure different things (this town's opinion of you vs the color's). Player-owned towns
    // are exempt entirely per explicit user decision ("the player's towns should not match any
    // color"), and non-color towns (Waste, Spawn) return 1.0 from colorOfTown() being null.
    private float colorReputationModifier() {
        PointOfInterest point = TileMapScene.instance().rootPoint;
        if (point == null)
            return 1f;
        if (TownRestoration.isTownRestored(WorldSave.getCurrentSave().peekPointOfInterestChanges(point.getID())))
            return 1f;
        return ColorReputation.getShopPriceMultiplier(ColorReputation.colorOfTown(point.getData()));
    }

    public MapStage getMapStage() {
        return stage;
    }

    @Override
    public void onPlayerCollide() {
        if (isDestroyed()) {
            stage.getPlayerSprite().stop();
            // Permanently-broken shop slots (2026-08-24 user report/spec: "please remove the
            // pop-up, saying the message board need to be built first. Those shops will always be
            // ruined, can't be repaired") - the ordinary locked-shop dialog below is actively
            // wrong here (it implies restoring the town's Job Board would eventually unlock a
            // repair option; a permanently-broken slot has no repair path at all, ever). Just
            // stop the player and do nothing else, same as walking into any other inert rubble.
            if (TownRestoration.isPermanentlyBrokenShop(stage, objectId))
                return;
            MapDialog dialog;
            if (!TownRestoration.isTownRestored(stage)) {
                dialog = TownRestoration.buildShopLockedDialog(stage, objectId);
            } else if (!TownRestoration.hasReputationForAnotherBuilding(stage.getChanges())) {
                dialog = TownRestoration.buildReputationLockedDialog(stage, objectId);
            } else if (fixedShop || EconomyBuildings.isSpecialShop(shopData)) {
                // Booster/Armory shops skip the Bank/Exchange/Industry conversion choice
                // entirely - see EconomyBuildings.buildSimpleRepairDialog().
                dialog = EconomyBuildings.buildSimpleRepairDialog(stage, objectId, shopData);
            } else {
                dialog = EconomyBuildings.buildChooseBuildingDialog(stage, objectId);
            }
            if (dialog.activate())
                stage.showDialog();
            return;
        }
        stage.getPlayerSprite().stop();
        PointOfInterestChanges changes = stage.getChanges();
        int economyType = EconomyBuildings.getBuildingType(changes, objectId);
        switch (economyType) {
            case EconomyBuildings.BANK:
                EconomyBuildings.openBankDialog(stage, changes, objectId);
                return;
            case EconomyBuildings.EXCHANGE:
                EconomyBuildings.openExchangeDialog(stage, objectId);
                return;
            case EconomyBuildings.TRADER:
                EconomyBuildings.openTraderDialog(stage, objectId);
                return;
            case EconomyBuildings.SHARD_MINE:
            case EconomyBuildings.GOLD_MINE:
            case EconomyBuildings.LUMBER_MILL:
            case EconomyBuildings.STONE_MINE:
                EconomyBuildings.openProductionInfoDialog(stage, economyType, objectId);
                return;
            case EconomyBuildings.OUTLOOK:
                EconomyBuildings.openOutlookInfoDialog(stage, objectId);
                return;
            case EconomyBuildings.TELEPORTER:
                EconomyBuildings.openTeleporterDialog(stage, objectId);
                return;
            case EconomyBuildings.ARCHAEOLOGIST:
                EconomyBuildings.openArchaeologistDialog(stage, objectId);
                return;
            default:
                // Guaranteed first-Armory Torch (user spec, 2026-08-13, redesigned 2026-08-14 -
                // see EconomyBuildings.injectGuaranteedTorchIfOwed()'s own comment for the full
                // history): now injected directly into the shop's for-sale stock at every
                // regeneration site instead of granted to inventory here on open - nothing to do
                // at collision time any more.
                // Straight into the shop - a destroyable shop's Destroy Building button lives on
                // the RewardScene page itself (user revision 2026-08-09; a first version's
                // Enter/Destroy/Leave pre-dialog cost an extra click on every visit).
                RewardScene.instance().loadRewards(rewardData, RewardScene.Type.Shop, this);
                Forge.switchScene(RewardScene.instance());
        }
    }

    /**
     * May this shop show a Destroy Building option (on its RewardScene page)? Plain Card Shops
     * and Booster shops in player-held wasteland towns only - Armory and the Capitol's fixed
     * land shops are on the user's excluded list, everything outside wasteland towns isn't
     * player-buildable at all, and economy-building conversions carry their Destroy in their own
     * dialogs already.
     */
    public boolean isDestroyable() {
        return TownRestoration.isWastelandTown() && !fixedShop
                && !EconomyBuildings.isArmoryShop(shopData)
                && TownRestoration.isShopRebuilt(stage, objectId)
                && EconomyBuildings.getBuildingType(stage.getChanges(), objectId) == EconomyBuildings.NONE;
    }

    public boolean isDestroyed() {
        // Permanently-broken shop slots (2026-08-24, Functioning Neutral Towns) - OR'd in, not a
        // replacement for the ordinary ruin check below. Renders/behaves exactly like an ordinary
        // ruined shop (broken sprite, locked-dialog on collide) but has no repair path at all -
        // these towns are never restored, so the flag this ORs against never gets set.
        return TownRestoration.isPermanentlyBrokenShop(stage, objectId)
                || (TownRestoration.isWastelandTown() && !TownRestoration.isShopRebuilt(stage, objectId));
    }

    @Override
    public void draw(Batch batch, float parentAlpha) {
        super.draw(batch, parentAlpha);
        if (isDestroyed()) {
            // Real art (64 variants, one picked stably per shop via objectId). Source art is
            // 32x32 (2x this shop's 16x16 footprint, deliberately - it's meant to loom over the
            // tile, not fill it) - draw at native size, centered over the footprint, rather than
            // squishing it down to getWidth()/getHeight() (which was both shrinking it and
            // muddying the detail via a forced downscale).
            TextureRegion brokenSprite = TownRestoration.getBrokenShopSprite(objectId);
            if (brokenSprite != null)
                drawOverFootprint(batch, brokenSprite);
        } else {
            // player_town.tmx has no baked-in building art at all anymore (see
            // MOD_CHANGELOG.md), so every rebuilt shop needs SOME icon drawn here, not just the
            // 6 economy building types - otherwise a rebuilt plain Card Shop is invisible. Every
            // OTHER town template (an AI color's own, whether straight from world-gen or a
            // mage/player capture via PointOfInterest.transformInto()) already has its own
            // building art baked into the tiles - drawing the plain/special/armory fallback icon
            // there duplicates what's already shown, hence gating it to isWastelandTown() only.
            // An actual economy-building conversion (Bank/Mine/etc) still needs its own icon
            // regardless of town template, since no baked art can represent a player's dynamic
            // choice there - getBuildingSprite() already returns null for NONE, so that path is
            // unaffected by this gate.
            int economyType = EconomyBuildings.getBuildingType(stage.getChanges(), objectId);
            TextureRegion buildingSprite;
            if (economyType == EconomyBuildings.TELEPORTER) {
                // Animated portal art, not a single static region - intercepted before the
                // generic getBuildingSprite() path (see its own comment on TELEPORTER).
                buildingSprite = EconomyBuildings.isTeleporterNetworkActive()
                        ? EconomyBuildings.getTeleporterActiveAnimation().getKeyFrame(teleporterAnimTime, true)
                        : EconomyBuildings.getTeleporterClosedSprite();
            } else {
                buildingSprite = EconomyBuildings.getBuildingSprite(economyType);
            }
            // isWastelandTownTemplate(), not isWastelandTown() (2026-08-24 fix) - this is asking
            // "does the current map lack baked building art", not "is this town ruined". A
            // functioning neutral town (NEUTRAL_SEEDED_FLAG) still renders from player_town.tmx,
            // which has no baked art either way - see TownRestoration.isWastelandTownTemplate()'s
            // own comment.
            if (buildingSprite == null && TownRestoration.isWastelandTownTemplate() && !fixedShop) {
                if (EconomyBuildings.isArmoryShop(shopData))
                    buildingSprite = EconomyBuildings.getArmoryShopSprite(stage.getChanges().getBuildingLevel(objectId));
                else if (EconomyBuildings.isSpecialShop(shopData))
                    buildingSprite = EconomyBuildings.getSpecialShopSprite();
                else
                    buildingSprite = EconomyBuildings.getPlainShopSprite();
            }
            if (buildingSprite != null)
                drawOverFootprint(batch, buildingSprite);
        }
    }

    // Source art for both broken-shop and building-icon overlays is 32x32 against this shop's
    // 16x16 footprint - draw at the texture's own native size instead of stretching/squishing it
    // to getWidth()/getHeight(), centered horizontally over the footprint (the doorstep tile the
    // player stands on to interact - the actual building looms above it).
    //
    // Vertical placement used to be derived from MapStage.getShopOverheadBounds() (the detected
    // baked-tile bounds), but player_town.tmx no longer has that baked art to detect at
    // all, and the couple of shops with a stray leftover tile were getting positioned off of
    // that single stray tile instead - worse than the plain fallback. A single fixed offset
    // (calibrated against user testing, including one round where ruins and building icons were
    // briefly given different offsets before testing showed they actually match) is simpler and
    // correct for every shop now.
    private void drawOverFootprint(Batch batch, TextureRegion region) {
        float w = region.getRegionWidth();
        float h = region.getRegionHeight();
        float x = getX() + (getWidth() - w) / 2f;
        float y = getY() + getHeight() - 16f;
        batch.draw(region, x, y, w, h);
    }


    public void setFixedShop(boolean fixedShop) {
        this.fixedShop = fixedShop;
    }

    // Weekly auto-refresh flag (2026-08-15) - set by MapStage from the tmx's own noRestock
    // property, since RewardScene can no longer infer it from !canRestock(): the widened ordinary
    // card shops now have BOTH a weekly auto-reseed and a paid restock button at once. Same
    // "MapStage tells the actor at construction" pattern as setFixedShop() above.
    private boolean weeklyRefresh;

    public void setWeeklyRefresh(boolean weeklyRefresh) {
        this.weeklyRefresh = weeklyRefresh;
    }

    public boolean isWeeklyRefresh() {
        return weeklyRefresh;
    }

    public boolean isUnlimited() {
        return shopData.unlimited;
    }

    @Override
    public String getName() {
        return shopData.name;
    }

    public String getDescription() {
        return shopData.description;
    }

    public int getRestockPrice() {
        return shopData.restockPrice;
    }

    public boolean canRestock() {
        return getRestockPrice() > 0;
    }

    public ShopData getShopData() {
        return shopData;
    }

    // Mod addition (Shop Type Re-Roll, 2026-08-11, round 8): lets MapStage.rerollShopType()'s
    // caller swap this actor's identity in place after a re-roll, instead of tearing down and
    // reconstructing the whole ShopActor.
    public void setShopData(ShopData data) {
        shopData = data;
    }

    public void setRewardData(Array<Reward> data) {
        rewardData = data;
    }

    public Array<Reward> getRewardData() {
        return rewardData;
    }
}
