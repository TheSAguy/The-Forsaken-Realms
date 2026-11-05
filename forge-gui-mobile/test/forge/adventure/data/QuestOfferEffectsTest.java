package forge.adventure.data;

import forge.adventure.pointofintrest.PointOfInterest;
import org.testng.AssertJUnit;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * TFR round 973 (Fable review P3-40): a job board's offer binds its places but their world side - the force-spawn or
 * +30 days, a dungeon's reset - waits for accept. A declined offer changes nothing; a quest issued any other way pays at
 * once; a taken offer pays once, before any stage activates.
 */
public class QuestOfferEffectsTest {
    private final List<String> applied = new ArrayList<>();
    private AdventureQuestData.WorldEffects real;

    @BeforeClass
    public void loadEnglish() {
        // new AdventureQuestData() reads its default declined text from the Localizer; the module's tests run in
        // forge-gui-mobile, beside forge-gui's language files.
        forge.util.Localizer.getInstance().initialize("en-US", "../forge-gui/res/languages/");
    }

    @BeforeMethod
    public void recordEffects() {
        real = AdventureQuestData.worldEffects;
        applied.clear();
        AdventureQuestData.worldEffects = (quest, stage, deferred) ->
                applied.add("stage " + stage.id + (deferred ? " at accept" : " at issue"));
    }

    @AfterMethod
    public void restoreEffects() {
        AdventureQuestData.worldEffects = real;
    }

    private static AdventureQuestStage boundStage(int id) {
        AdventureQuestStage stage = new AdventureQuestStage();
        stage.id = id;
        stage.setTargetPOI(new PointOfInterest());
        return stage;
    }

    @Test
    public void anOfferAppliesNothingUntilItIsTaken() {
        AdventureQuestData offer = new AdventureQuestData();
        offer.beginOffer();
        offer.oweOrApplyTargetEffects(boundStage(1));
        offer.oweOrApplyTargetEffects(boundStage(2));
        AssertJUnit.assertTrue("a declined offer is simply dropped: nothing may have happened yet", applied.isEmpty());
    }

    @Test
    public void takingTheOfferAppliesEachStageOnceInOrder() {
        AdventureQuestData offer = new AdventureQuestData();
        offer.beginOffer();
        offer.oweOrApplyTargetEffects(boundStage(1));
        offer.oweOrApplyTargetEffects(boundStage(2));
        offer.activateNextStages(); // AdventurePlayer.addQuest's first call on the taken quest
        AssertJUnit.assertEquals(Arrays.asList("stage 1 at accept", "stage 2 at accept"), applied);
        offer.activateNextStages(); // every later quest event
        offer.applyDeferredWorldEffects();
        AssertJUnit.assertEquals(2, applied.size());
    }

    @Test
    public void aStageWithNoPlaceOwesNothing() {
        AdventureQuestData offer = new AdventureQuestData();
        offer.beginOffer();
        AdventureQuestStage unbound = new AdventureQuestStage();
        unbound.id = 1;
        offer.oweOrApplyTargetEffects(unbound);
        offer.applyDeferredWorldEffects();
        AssertJUnit.assertTrue(applied.isEmpty());
    }

    @Test
    public void aQuestIssuedWithoutABoardAppliesAtOnce() {
        AdventureQuestData issued = new AdventureQuestData(); // a dialog, the main quest, the console: generateQuest()
        issued.oweOrApplyTargetEffects(boundStage(1));
        AssertJUnit.assertEquals(Arrays.asList("stage 1 at issue"), applied);
        issued.activateNextStages();
        AssertJUnit.assertEquals(1, applied.size());
    }

    @Test
    public void aLoadedQuestOwesNothing() {
        AdventureQuestData loaded = new AdventureQuestData(); // the pending fields are transient: a save carries none
        loaded.activateNextStages();
        loaded.applyDeferredWorldEffects();
        AssertJUnit.assertTrue(applied.isEmpty());
    }
}
