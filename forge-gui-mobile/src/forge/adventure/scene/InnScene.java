package forge.adventure.scene;

import com.badlogic.gdx.scenes.scene2d.ui.Image;
import com.github.tommyettinger.textra.TextraButton;
import com.github.tommyettinger.textra.TextraLabel;
import forge.Forge;
import forge.adventure.data.AdventureEventData;
import forge.adventure.player.AdventurePlayer;
import forge.adventure.pointofintrest.PointOfInterest;
import forge.adventure.pointofintrest.PointOfInterestChanges;
import forge.adventure.stage.GameHUD;
import forge.adventure.util.AdventureEventController;
import forge.adventure.util.ColorReputation;
import forge.adventure.util.Config;
import forge.adventure.util.Controls;
import forge.adventure.util.Current;
import forge.adventure.util.TownRestoration;
import forge.adventure.world.WorldSave;
import forge.model.CardBlock;

/**
 * Scene for the Inn in towns
 */
public class InnScene extends UIScene {
    private static InnScene object;
    private static int localObjectId;
    private static String localPointOfInterestId;
    private static AdventureEventData localEvent;
    Scene lastGameScene;
    public static InnScene instance() {
        return instance(null, "", null, -1);
    }

    public static InnScene instance(Scene lastGameScene, String pointOfInterestId, PointOfInterestChanges localChanges, int objectId){
        if(object==null)
            object=new InnScene();

        changes = localChanges;
        localPointOfInterestId = pointOfInterestId;
        localObjectId = objectId;
        if (lastGameScene != null)
            object.lastGameScene=lastGameScene;
        initLocalEvent();

        return object;
    }


    TextraButton tempHitPointCost, sell, leave, event, reroll;
    Image healIcon, sellIcon, leaveIcon;
    private TextraLabel playerGold,playerShards,eventDescription;

    private InnScene() {

        super(Forge.isLandscapeMode() ? "ui/inn.json" : "ui/inn_portrait.json");
        tempHitPointCost = ui.findActor("tempHitPointCost");
        ui.onButtonPress("done", InnScene.this::done);
        ui.onButtonPress("tempHitPointCost", InnScene.this::potionOfFalseLife);
        ui.onButtonPress("sell", InnScene.this::sell);
        leave = ui.findActor("done");
        sell = ui.findActor("sell");
        playerGold = Controls.newAccountingLabel(ui.findActor("playerGold"), false);
        playerShards = Controls.newAccountingLabel(ui.findActor("playerShards"),true);

        leaveIcon = ui.findActor("leaveIcon");
        healIcon = ui.findActor("healIcon");
        sellIcon = ui.findActor("sellIcon");

        event = ui.findActor("event");
        eventDescription = ui.findActor("eventDescription");

        ui.onButtonPress("event", InnScene.this::startEvent);

        reroll = ui.findActor("reroll");
        ui.onButtonPress("reroll", InnScene.this::promptRerollEvent);
    }



    public void done() {
        GameHUD.getInstance().getTouchpad().setVisible(false);
        Forge.switchScene(lastGameScene==null?GameScene.instance():lastGameScene);
    }

    public void potionOfFalseLife() {
        // Ruined-town Inn (user spec 2026-08-31): tournaments only - no card sales, no extra life.
        // Re-checked here rather than trusting refreshStatus()'s setDisabled(), which does NOT
        // detach this click handler in this UI framework.
        if (isRuinedTown())
            return;
        // Color reputation (MOD_SCOPE.md #1): War-tier towns bar healing outright. (Partner-tier
        // needs no server-side guard here - the free overheal already puts life above maxLife,
        // and AdventurePlayer.potionOfFalseLife() only fires when life == maxLife.)
        String repColor = currentRepColor();
        if (repColor != null && ColorReputation.isHealBarred(repColor))
            return;
        if (Current.player().potionOfFalseLife()){
            refreshStatus();
        }
    }

    /**
     * Is this Inn standing in a town that is still RUINED? (user spec 2026-08-31: "let's make
     * ruined towns, the Inn, you can't Sell Cards and Buy Extra live. Only the Tournament option").
     * <p>
     * Both halves matter. isWastelandTown() alone is a question about the POI's own tags and stays
     * true after the player rebuilds the place, which would leave a fully restored town's Inn
     * permanently crippled; isTownRestored() alone is false in an ordinary color town too. Note
     * isWastelandTown() already exempts neutral-seeded towns, so a functioning Neutral town's Inn
     * keeps every option - which until now was the ONLY behaviour either kind of town had, since
     * ruined and Neutral towns both report no color and were therefore indistinguishable here.
     */
    private boolean isRuinedTown() {
        return TownRestoration.isWastelandTown() && !TownRestoration.isTownRestored(changes);
    }

    // Color reputation (MOD_SCOPE.md #1): the color of the town this Inn is in, or null if this
    // town matches no color (Waste/Spawn) or is player-owned (exempt from all color effects, same
    // pattern as ShopActor.colorReputationModifier()).
    private String currentRepColor() {
        PointOfInterest point = TileMapScene.instance().rootPoint;
        if (point == null)
            return null;
        if (TownRestoration.isTownRestored(WorldSave.getCurrentSave().peekPointOfInterestChanges(point.getID())))
            return null;
        return ColorReputation.colorOfTown(point.getData());
    }

    @Override
    public void act(float delta) {
        stage.act(delta);
    }


    @Override
    public void render() {
        super.render();
    }

    int tempHealthCost = 0;
    static PointOfInterestChanges changes;

    @Override
    public void enter() {
        super.enter();
        refreshStatus();
        GameHUD.getInstance().updateBGM();
    }

    private void refreshStatus(){
        float townPriceModifier = changes == null ? 1f : changes.getTownPriceModifier();
        tempHealthCost = Math.round(Current.player().falseLifeCost() * townPriceModifier);
        boolean purchaseable = Current.player().getMaxLife() == Current.player().getLife() &&
                tempHealthCost <= Current.player().getGold();

        // Color reputation (MOD_SCOPE.md #1): War bars healing outright; Partner is greyed out for
        // the opposite reason - the free overheal (AdventurePlayer.grantPartnerOverheal(), granted
        // on entering this town) already covers it, so a purchase would be redundant.
        String repColor = currentRepColor();
        ColorReputation.Status repStatus = repColor == null ? null : ColorReputation.getStatus(repColor);
        // Ruined town: the innkeeper runs a tournament and nothing else. Checked before the color
        // tiers because a ruined town has no color at all, so none of those branches would fire.
        boolean ruined = isRuinedTown();
        sell.setDisabled(ruined);
        if (sellIcon != null)
            sellIcon.setVisible(!ruined);
        if (ruined) {
            tempHitPointCost.setDisabled(true);
            tempHitPointCost.setText("Closed");
        } else if (repStatus == ColorReputation.Status.WAR) {
            tempHitPointCost.setDisabled(true);
            tempHitPointCost.setText("Barred");
        } else if (repStatus == ColorReputation.Status.PARTNER) {
            tempHitPointCost.setDisabled(true);
            tempHitPointCost.setText("Blessed");
        } else {
            tempHitPointCost.setDisabled(!purchaseable);
            tempHitPointCost.setText("[+GoldCoin] " + tempHealthCost);
        }

        initLocalEvent();
        if (localEvent == null){
            eventDescription.setText("[GREY]No events at this time");
            event.setDisabled(true);
            reroll.setDisabled(true);
        }
        else{
            event.setDisabled(false);
            // Re-roll (2026-08-24 user spec) only makes sense before the player has entered -
            // once Entered/Ready/Started/Completed/Awarded, the deck build or bracket is already
            // committed, so re-rolling the pool underneath it would orphan that progress.
            // Greyed out when unaffordable too, not just when unavailable (2026-08-26 user
            // request: "apply this logic to all buy/upgrade/re-roll buttons" - promptRerollEvent()
            // already re-checks this itself before spending, but nothing previously reflected it
            // in the button's own visual state).
            reroll.setDisabled(localEvent.eventStatus != AdventureEventController.EventStatus.Available
                    || AdventurePlayer.current().getShards() < Config.instance().getTuningData().innTournamentRerollShardCost);
            reroll.setText("[%80]Re-roll (" + Config.instance().getTuningData().innTournamentRerollShardCost + " [+Shards])");
            switch (localEvent.eventStatus){
                case Available:
                    eventDescription.setText(localEvent.format.toString() + " available");
                    break;
                case Entered:
                    eventDescription.setText(localEvent.format.toString() + " [GREEN]entered");
                    break;
                case Ready:
                    eventDescription.setText(localEvent.format.toString() + " [GREEN]ready");
                    break;
                case Started:
                    eventDescription.setText(localEvent.format.toString() + " [GREEN]in progress");
                    break;
                case Completed:
                    eventDescription.setText(localEvent.format.toString() + " [GREEN]rewards available");
                    break;
                case Awarded:
                    eventDescription.setText(localEvent.format.toString() + " complete");
                    break;
                case Abandoned:
                    eventDescription.setText(localEvent.format.toString() + " [RED]abandoned");
                    event.setDisabled(true);
                    reroll.setDisabled(true);
                    break;
            }
        }
    }

    private void sell() {
        // See potionOfFalseLife() - setDisabled() does not detach the handler.
        if (isRuinedTown())
            return;
        ShopScene.instance().loadChanges(changes);
        Forge.switchScene(ShopScene.instance());
    }

    private static void initLocalEvent() {
        localEvent = null;
        for (AdventureEventData data :  AdventurePlayer.current().getEvents()){
            if (data.sourceID.equals(localPointOfInterestId) && data.eventOrigin == localObjectId){
                localEvent = data;
                return;
            }
        }
        AdventureEventController controller = AdventureEventController.instance();
        localEvent = controller.createEvent(localPointOfInterestId);
        if(localEvent != null)
            controller.initializeEvent(localEvent, localPointOfInterestId, localObjectId, changes);
    }

    // Inn Tournament Re-roll (2026-08-24, user spec: "let the player be able to re-roll the
    // tournament draft set. for 15 gems. Remember to keep the sets gated to each Inn/color
    // though. Only re-roll from the pool they are allowed."). Draws the replacement CardBlock via
    // AdventureEventData.pickCardBlockByFormat() - the exact same EditionProgression-gated picker
    // the original roll used - so the re-roll can never surface an edition this Inn/color combo
    // wouldn't already offer. New block is picked BEFORE charging shards, mirroring RewardScene's
    // promptRerollShopType(): pick first, only charge once a real (and different, see below) block
    // is confirmed.
    //
    // Must-actually-change guarantee (2026-08-24 follow-up user request: "make sure it changes.
    // Can't be the same one from current expansion set.") - retries the pick up to MAX_REROLL_
    // ATTEMPTS times, rejecting any candidate matching the currently-showing block's name. Bounded
    // rather than looping forever, since an extremely narrow edition-progression pool (e.g. the
    // player has only unlocked one legal edition so far) could otherwise never produce a different
    // result at all - in that case this behaves like the pool-exhausted case below: no charge, no
    // change, same as if the picker itself had returned null.
    private static final int MAX_REROLL_ATTEMPTS = 20;

    private void promptRerollEvent() {
        if (localEvent == null || localEvent.eventStatus != AdventureEventController.EventStatus.Available)
            return;
        int cost = Config.instance().getTuningData().innTournamentRerollShardCost;
        if (AdventurePlayer.current().getShards() < cost)
            return;
        showDialog(createGenericDialog("", "Re-roll this Inn's tournament for " + cost
                        + " [+Shards]?\nPicks a new random card pool from the same allowed editions.",
                Forge.getLocalizer().getMessage("lblYes"), Forge.getLocalizer().getMessage("lblNo"), () -> {
                    removeDialog();
                    AdventureEventController.EventFormat format = localEvent.format;
                    String currentBlockName = localEvent.cardBlockName;
                    CardBlock newBlock = null;
                    for (int attempt = 0; attempt < MAX_REROLL_ATTEMPTS; attempt++) {
                        CardBlock candidate = AdventureEventData.pickCardBlockByFormat(format);
                        if (candidate == null)
                            break; // no legal pool to draw from at all
                        if (currentBlockName == null || !candidate.getName().equals(currentBlockName)) {
                            newBlock = candidate;
                            break;
                        }
                    }
                    if (newBlock == null)
                        return; // no different block available - no charge, no change
                    AdventurePlayer.current().takeShards(cost);
                    replaceLocalEvent(format, newBlock);
                    refreshStatus();
                }, this::removeDialog));
    }

    public static void replaceLocalEvent(AdventureEventController.EventFormat format, CardBlock cardBlock) {
        AdventurePlayer.current().getEvents().removeIf((data) -> data.sourceID.equals(localPointOfInterestId) && data.eventOrigin == localObjectId);
        AdventureEventController controller = AdventureEventController.instance();
        localEvent = controller.createEvent(format, cardBlock, localPointOfInterestId);
        if(localEvent != null)
            controller.initializeEvent(localEvent, localPointOfInterestId, localObjectId, changes);
    }

    private void startEvent(){

        Forge.switchScene(EventScene.instance(this, localEvent, changes), true);

    }

}
