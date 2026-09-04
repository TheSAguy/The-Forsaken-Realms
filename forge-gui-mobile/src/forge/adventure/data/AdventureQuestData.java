package forge.adventure.data;

import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.utils.Array;
import forge.Forge;
import forge.adventure.character.EnemySprite;
import forge.adventure.pointofintrest.PointOfInterest;
import forge.adventure.scene.GameScene;
import forge.adventure.scene.TileMapScene;
import forge.adventure.stage.GameHUD;
import forge.adventure.stage.MapStage;
import forge.adventure.util.AdventureQuestController;
import forge.adventure.util.AdventureQuestEvent;
import forge.adventure.util.Current;
import forge.adventure.world.WorldSave;
import forge.util.Aggregates;

import java.io.Serializable;
import java.util.*;

import static forge.adventure.util.AdventureQuestController.QuestStatus.*;

public class AdventureQuestData implements Serializable {

    // 2026-08-29 save-compat fix: this class had NO explicit serialVersionUID (unlike every
    // sibling data class in this package - AdventureQuestStage/DialogData/RewardData all pin
    // one). Without it, adding ANY field silently changes javac's auto-generated UID, and this
    // engine's save loader (SaveFileData.DecompressibleInputStream) force-overrides UID
    // mismatches instead of rejecting them - which corrupts the byte stream rather than
    // rejecting cleanly, because the override only works when the class's actual field LAYOUT
    // still matches what the old save was written with. Adding offerProbability (see below)
    // broke every existing save's quest list this way. Pinning a UID here doesn't undo that by
    // itself (see the field's own transient fix), but stops the NEXT field addition from
    // repeating this failure silently.
    private static final long serialVersionUID = 1L;

    private int id;

    public int getID(){
        if (isTemplate && id < 1) {
            id = AdventureQuestController.instance().getNextQuestID();
        }
        return id;
    }
    public boolean isTemplate = false;
    public String name = "";
    public String description = "";
    public String synopsis =""; //Intended for Dev Mode only at most
    public transient boolean completed = false;
    public transient boolean failed = false;
    // NOT transient (2026-08-15 bug fix) - these two specifically must survive save/load. Being
    // transient meant every load reset them to false, so a still-active quest's prologue dialog
    // (here, quest 28's one-time "Skip tutorial" item grant - a teleport rune + starting
    // Challenge Coins) kept re-triggering on the next showQuestDialogs() call after every
    // save/reload, letting the player collect its one-time grant more than once (user report:
    // "received twice the starting coins / teleporter item"). completed/failed stay transient -
    // untouched here, that's a separate question not confirmed to have the same bug.
    private boolean prologueDisplayed = false;
    private boolean epilogueDisplayed = false;

    public DialogData offerDialog;
    public DialogData prologue;
    public DialogData epilogue;
    public DialogData failureDialog;

    public DialogData declinedDialog;

    public RewardData reward;
    public String rewardDescription = "";

    public AdventureQuestStage[] stages = new AdventureQuestStage[0];
    public String[] questSourceTags = new String[0];
    // Round 107 (user spec 2026-09-04): template-only gates for the AI capitals' "capture an enemy town" quests -
    // offered only while the player's standing with giverColor equals requiredColorStatus ("Partner").
    public String giverColor;
    public String requiredColorStatus;
    public String[] questEnemyTags = new String[0];
    public String[] questPOITags = new String[0];
    private transient EnemySprite targetEnemySprite = null;
    private PointOfInterest targetPoI = null;
    Dictionary<String, PointOfInterest> poiTokens = new Hashtable<>();
    Dictionary<String, String> poiBiomeTokens = new Hashtable<>();
    Dictionary<String, EnemyData> enemyTokens = new Hashtable<>();
    Dictionary<String, String> otherTokens = new Hashtable<>();
    public boolean storyQuest = false;
    // NOTE (2026-08-29): low-probability quest offering ("offerProbability" in quests.json) is
    // deliberately NOT a field on this class - see AdventureQuestController.questOfferProbability
    // for why and how it's read instead. Adding it back here as a plain field reintroduces
    // exactly the bug that field caused twice in one day: transient hides it from libGDX's Json
    // loader too (not just java.io.Serializable), and non-transient corrupts every existing
    // save's quest list (this class has no explicit-UID field-count slack - see the
    // serialVersionUID comment above). Any future per-template-only quest metadata should
    // follow the same out-of-band pattern, not a field here.
    public boolean isTracked = false;
    public boolean autoTrack = false;
    public String sourceID = "";

    public String getName() {
        return name;
    }
    public String getDescription() {
        return description;
    }
    public RewardData getReward() {
        return reward;
    }

    public AdventureQuestData(AdventureQuestData data){
        id = data.id;
        isTemplate = false; //Anything being copied is by definition not a template
        name = data.name;
        description = data.description;
        synopsis = data.synopsis;
        offerDialog = new DialogData(data.offerDialog);
        prologue = new DialogData(data.prologue);
        epilogue = new DialogData(data.epilogue);
        failureDialog = new DialogData(data.failureDialog);
        declinedDialog = new DialogData(data.declinedDialog);
        reward = new RewardData(data.reward);
        rewardDescription = data.rewardDescription;
        completed = data.completed;
        stages = new AdventureQuestStage[data.stages.length];
        for (int i = 0; i < stages.length; i++){
            stages[i] = new AdventureQuestStage(data.stages[i]);
        }
        questSourceTags = data.questSourceTags.clone();
        questPOITags = data.questPOITags.clone();
        questEnemyTags = data.questEnemyTags.clone();
        targetPoI = data.targetPoI;
        targetEnemySprite = data.targetEnemySprite;
        storyQuest = data.storyQuest;
        sourceID = data.sourceID;
        poiTokens = data.poiTokens;
        enemyTokens = data.enemyTokens;
        otherTokens = data.otherTokens;
        isTracked = data.isTracked;
    }

    public AdventureQuestData()
    {
        declinedDialog = new DialogData();
        declinedDialog.text = Forge.getLocalizer().getMessage("advDefaultDeclinedDialog");
        DialogData dismiss = new DialogData();
        dismiss.name = "(Catching the not so subtle hint, you leave.)";
        declinedDialog.options = new DialogData[1];
        declinedDialog.options[0] = dismiss;
    }

    public List<AdventureQuestStage> getActiveStages(){
        List<AdventureQuestStage> toReturn = new ArrayList<>();

        //Temporarily allow only one active stage until parallel stages and prerequisites are implemented
        for (AdventureQuestStage stage : stages) {
            if (stage.getStatus() == ACTIVE) {
                toReturn.add(stage);
            }
        }
        return toReturn;
    }

    public List<AdventureQuestStage> getCompletedStages(){
        List<AdventureQuestStage> toReturn = new ArrayList<>();

        for (AdventureQuestStage stage : stages) {
            if (stage.getStatus() == COMPLETE)
                toReturn.add(stage);
        }
        return toReturn;
    }

    public List<Integer> getCompletedStageIDs(){
        List<Integer> toReturn = new ArrayList<>();

        for (AdventureQuestStage stage : getCompletedStages()) {
            toReturn.add(stage.id);
        }
        return toReturn;
    }

    public PointOfInterest getTargetPOI() {

        for (AdventureQuestStage stage : getActiveStages()) {
            targetPoI = stage.getTargetPOI();
            if (targetPoI != null)
                break;
        }

        return targetPoI;
    }

    /**
     * Every stage target this quest still NEEDS - active stages AND not-yet-activated later
     * stages alike (2026-08-16 review finding: initialize() binds every stage's target up
     * front, but DungeonRotation's despawn protection consulted only getTargetPOI(), which
     * filters to ACTIVE stages - so a Clear stage sitting behind an unmet prerequisite had no
     * protection and its dungeon could rotate away before the player ever reached that stage).
     * Completed stages are excluded - their target is done with and free to despawn normally.
     */
    public List<PointOfInterest> getAllPendingTargetPOIs() {
        List<PointOfInterest> ret = new ArrayList<>();
        for (AdventureQuestStage stage : stages) {
            if (stage.getStatus() == COMPLETE)
                continue;
            if (stage.getTargetPOI() != null)
                ret.add(stage.getTargetPOI());
        }
        return ret;
    }

    public EnemySprite getTargetEnemySprite(){
        if (targetEnemySprite == null){
            for (AdventureQuestStage stage : getActiveStages()) {
                targetEnemySprite = stage.getTargetSprite();
                if (targetEnemySprite != null){
                    break;
                }
            }
        }
        return targetEnemySprite;
    }

    public void initialize(){
        poiTokens = new Hashtable<>();

        for (AdventureQuestStage stage : stages){
            initializeStage(stage);
        }

        replaceTokens();
    }

    public void initializeStage(AdventureQuestStage stage){
        if (stage == null || stage.objective == null) return;

        stage.initialize();

        switch  (stage.objective){
            case Arena:
                stage.setTargetPOI(poiTokens, this.name);
                break;
            case Clear:
                stage.setTargetPOI(poiTokens, this.name);
                break;
            case CompleteQuest:
                stage.setTargetPOI(poiTokens, this.name);
            case Defeat:
                stage.setTargetPOI(poiTokens, this.name);
                if (!stage.mixedEnemies)
                    stage.setTargetEnemyData(generateTargetEnemyData(stage));
                break;
            case Delivery:
                stage.setTargetPOI(poiTokens, this.name);
                //Set delivery item as a miscellaneous token
                break;
            case Escort:
                //add configuration of what is being escorted.
                stage.setTargetPOI(poiTokens, this.name);
                if (!stage.mixedEnemies)
                    stage.setTargetEnemyData(generateTargetEnemyData(stage));
                break;
            case Fetch:
                stage.setTargetPOI(poiTokens, this.name);
            case Hunt:
                stage.setTargetSprite(generateTargetEnemySprite(stage));
                break;
            case Leave:
                stage.setTargetPOI(poiTokens, this.name);
                break;
            case MapFlag:
                stage.setTargetPOI(poiTokens, this.name);
                break;
            case Patrol:
                //Need ability to set a series of target coordinates that can be reached, point nav arrow to them
                // This might get oddly complex.
                break;
            case QuestFlag:
                stage.setTargetPOI(poiTokens, this.name);
                break;
            case Rescue:
                stage.setTargetPOI(poiTokens, this.name);
                break;
            case Travel:
                stage.setTargetPOI(poiTokens, this.name);
        }

        if (stage.getTargetPOI() != null
                && ("cave".equalsIgnoreCase( stage.getTargetPOI().getData().type)
                || "dungeon".equalsIgnoreCase( stage.getTargetPOI().getData().type))){
            //todo: decide how to handle this in "anyPOI" scenarios
            WorldSave.getCurrentSave().getPointOfInterestChanges(stage.getTargetPOI().getID()).clearDeletedObjects();
        }

        PointOfInterest temp = stage.getTargetPOI();
        if (temp != null) {
            poiTokens.put("$(poi_" + stage.id + ")", temp);
            poiBiomeTokens.put("$(biome_" + stage.id + ")", GameScene.instance().getBiomeByPosition(temp.getPosition()));
        }

        EnemyData target = stage.getTargetEnemyData();
        if (target != null) {
            enemyTokens.put("$(enemy_" + stage.id + ")", target);
        }

        otherTokens.put("$(playername)", Current.player().getName());
        otherTokens.put("$(currentbiome)", GameScene.instance().getAdventurePlayerLocation(false,true));
        otherTokens.put("$(playerrace)", Current.player().raceName());
    }

    public void replaceTokens(){
        replaceTokens(offerDialog);
        replaceTokens(prologue);
        replaceTokens(epilogue);
        replaceTokens(failureDialog);
        replaceTokens(declinedDialog);

        name = replaceTokens(name);
        description = replaceTokens(description);
        rewardDescription = replaceTokens(rewardDescription);

        for (AdventureQuestStage stage: stages)
        {
            replaceTokens(stage);
        }
    }

    private void replaceTokens(AdventureQuestStage stage){
        replaceTokens(stage.prologue);
        replaceTokens(stage.epilogue);
        replaceTokens(stage.failureDialog);
        stage.name = replaceTokens(stage.name);
        stage.description = replaceTokens(stage.description);
    }

    private String replaceTokens(String data){
        for (Enumeration<String> e = poiTokens.keys(); e.hasMoreElements();){
            String key = e.nextElement();
            data = data.replace(key, poiTokens.get(key).getDisplayName());
        }
        for (Enumeration<String> e = poiBiomeTokens.keys(); e.hasMoreElements();){
            String key = e.nextElement();
            data = data.replace(key, poiBiomeTokens.get(key));
        }
        for (Enumeration<String> enemy = enemyTokens.keys(); enemy.hasMoreElements();){
            String enemyKey = enemy.nextElement();
            data = data.replace(enemyKey, enemyTokens.get(enemyKey).getName());
        }
        for (Enumeration<String> other = otherTokens.keys(); other.hasMoreElements();){
            String key = other.nextElement();
            data = data.replace(key, otherTokens.get(key));
        }
        return data;
    }

    private void replaceTokens(DialogData data){
        for (DialogData option : data.options){
            replaceTokens(option);
        }
        for (Enumeration<String> e = poiTokens.keys(); e.hasMoreElements();){
            String key = e.nextElement();
            data.text = data.text.replace(key, poiTokens.get(key).getDisplayName());
            data.name = data.name.replace(key, poiTokens.get(key).getDisplayName());
        }

        for (Enumeration<String> e = poiBiomeTokens.keys(); e.hasMoreElements();){
            String key = e.nextElement();
            data.text = data.text.replace(key, poiBiomeTokens.get(key));
            data.name = data.name.replace(key, poiBiomeTokens.get(key));
        }

        for (Enumeration<String> e = enemyTokens.keys(); e.hasMoreElements();){
            String key = e.nextElement();
            data.text = data.text.replace(key, enemyTokens.get(key).getName());
            data.name = data.name.replace(key, enemyTokens.get(key).getName());
        }

        for (Enumeration<String> other = otherTokens.keys(); other.hasMoreElements();){
            String key = other.nextElement();
            data.text = data.text.replace(key, otherTokens.get(key));
            data.name = data.name.replace(key, otherTokens.get(key));
        }

        for (DialogData.ActionData ad: data.action) {
            if ( ad != null && ad.POIReference != null)
            {
                for (Enumeration<String> e = poiTokens.keys(); e.hasMoreElements(); ) {
                    String key = e.nextElement();
                    ad.POIReference = ad.POIReference.replace(key, poiTokens.get(key).getID());
                }
            }
        }
    }

    private EnemySprite generateTargetEnemySprite(AdventureQuestStage stage){
        return generateTargetEnemySprite(stage, true);
    }

    private EnemySprite generateTargetEnemySprite(AdventureQuestStage stage, boolean genNewData){
        if (stage.objective == AdventureQuestController.ObjectiveTypes.Hunt){
            EnemyData toUse = null;
            if (genNewData) {
                toUse = generateTargetEnemyData(stage);
            } else {
                toUse = enemyTokens.get("$(enemy_" + stage.id +")");
            }

            toUse.lifetime = stage.count3;
            EnemySprite toReturn =  new EnemySprite(toUse);
            toReturn.questStageID = stage.stageID.toString();
            return toReturn;
        }
        return null;
    }

    private EnemyData generateTargetEnemyData(AdventureQuestStage stage)
    {
        ArrayList<EnemyData> matchesTags = new ArrayList<>();
        for(EnemyData data: new Array.ArrayIterator<>(WorldData.getAllEnemies())) {
            ArrayList<String> candidateTags = new ArrayList<>(Arrays.asList(data.questTags));
            int tagCount = candidateTags.size();

            candidateTags.removeAll(stage.enemyExcludeTags);
            if (candidateTags.size() != tagCount) {
                continue;
            }

            candidateTags.removeAll(stage.enemyTags);
            if (candidateTags.size() == tagCount - stage.enemyTags.size()) {
                matchesTags.add(data);
            }
        }
        // Sanity-filter the pool (2026-08-27, same bug class as getExtraQuestSpawns): the raw
        // tag scan is full of spawnRate:0 legends and above-rank entries. Unlike the extra-spawn
        // path, a Hunt/Defeat stage MUST get a target, so an over-filtered pool falls back to
        // the unfiltered one rather than returning nothing.
        java.util.List<EnemyData> pool;
        if (matchesTags.isEmpty()) {
            pool = new ArrayList<>();
            for (EnemyData data : new Array.ArrayIterator<>(WorldData.getAllEnemies()))
                pool.add(data);
        } else {
            pool = matchesTags;
        }
        java.util.List<EnemyData> filtered = forge.adventure.util.AdventureQuestController
                .filterQuestSpawnPool(pool, forge.adventure.util.Current.player().getStatistic().rank());
        if (!filtered.isEmpty())
            pool = filtered;
        else
            System.out.println("[TFR-Spawn] quest-target pool for stage \"" + stage.name
                    + "\" empty after sanity filter - using unfiltered pool (" + pool.size() + " candidates)");
        return new EnemyData(Aggregates.random(pool));
    }



    class questUpdate {

    }

    public void updateStages(AdventureQuestEvent event){
        boolean done = true;
        if (event.poi == null && MapStage.getInstance().isInMap())
            event.poi = TileMapScene.instance().rootPoint;
        for (AdventureQuestStage stage: stages) {
            switch (stage.getStatus()) {
                case ACTIVE:
                    done = stage.handleEvent(event) == COMPLETE && done;
                    break;
                case COMPLETE:
                    continue;
                default:
                    done = false;
                    break;
            }
            failed |= stage.getStatus() == FAILED;
        }
        completed = done;
    }

    public DialogData getPrologue() {
        if (!prologueDisplayed) {
            prologueDisplayed = true;
            return prologue;
        }
        return null;
    }

    public DialogData getEpilogue() {
        if (!epilogueDisplayed) {
            epilogueDisplayed = true;
            return epilogue;
        }
        return null;
    }

    public void fail(){
        failed = true;
        isTracked = false;
        //todo: handle any necessary cleanup or reputation loss
    }

    public void activateNextStages() {
        boolean showNotification = false;
        // Stabilization loop (2026-08-26 user request: "add safeguards if the player does
        // something before a quest. Like builds a capitol, before the quest fires"): a
        // newly-activated flag stage whose flag is ALREADY satisfied retro-completes on the spot
        // (AdventureQuestStage.retroCompleteIfFlagSatisfied(), same activation-time pattern as
        // Fetch's inventory poll below) - and its completion may unlock the NEXT stage's
        // prerequisites within this same call, so the pass repeats until nothing else activates
        // or retro-completes. Bounded by the stage count; a player who already built their
        // Capitol, hired a guard, and built a mine before "Raise the Banner" is issued sails
        // straight through those stages the moment the quest activates.
        boolean changedThisPass = true;
        while (changedThisPass) {
            changedThisPass = false;
            for (AdventureQuestStage s : stages) {
                if (s.getStatus() == INACTIVE){
                    s.checkPrerequisites(getCompletedStageIDs());
                    if (s.getStatus() == ACTIVE) {
                        changedThisPass = true;
                        if (s.hasRequiredFetchItems()) {
                            s.handleEvent(new AdventureQuestEvent());
                        }
                        if (s.retroCompleteIfFlagSatisfied())
                            continue; // completed instantly - no sprites/notification for it
                        AdventureQuestController.instance().addQuestSprites(s);
                        showNotification = true;
                    }
                }
            }
        }
        if (showNotification) {
            StringBuilder description = new StringBuilder();
            // "Quest Updated:" header (mod, 2026-08-08): this notification fires whenever a stage
            // newly activates - on accept, and again mid-quest each time an objective unlocks.
            // Without the header the mid-quest firings read as unexplained quest popups
            // (user report: "I received a few messages that I did not understand").
            description.append("Quest Updated: [!]").append(name).append("[]");
            for (AdventureQuestStage stage : getActiveStages()) {
                description.append("\n")
                        .append(stage.name).append("\n[/]")
                        //.append(stage.description.length()<=50?stage.description:stage.description.substring(0,49) + "...")
                        .append(stage.description)
                        .append("[]");
            }
            GameHUD.getInstance().addNotification(description.toString());
        }
    }

    public PointOfInterest getClosestValidPOI(Vector2 pos) {
        List<PointOfInterest> validPOIs = new ArrayList<>();
        for (AdventureQuestStage stage : getActiveStages()) {
            // getNavPOIs, not getValidPOIs: identical for every stage except the navPOIFilter
            // world-map flag stages, whose arrow target is navigation-only (see its comment).
            validPOIs.addAll(stage.getNavPOIs());
        }
        if (validPOIs.isEmpty())
            return null;
        validPOIs.sort(Comparator.comparingInt(a -> (int) a.getPosition().dst(pos)));
        return validPOIs.get(0);
    }

    /* Check if the player created an On the Hunt quest, left the city,
     * saved, and then reloaded, such that the sprite for the enemy was never generated or failed to
     * be added to save data properly
     * @param AdventureQuestStage stage The specific step to check if it meets the criteria that defines this use case
     * @param boolean Whether the quest meets the detach criteria
     */
    public boolean qualifiesForDetachedQuest(AdventureQuestStage stage) {
        return (stage.objective == AdventureQuestController.ObjectiveTypes.Hunt
        && enemyTokens.size() > 0
        && targetEnemySprite == null);
    }

    /* If the player is in fact detached, fix that specific case
     * @param AdventureQuestStage stage The stage whose state should be fixed
     */
    public void fixOrphanedHuntQuest(AdventureQuestStage stage) {
        EnemySprite toSet = generateTargetEnemySprite(stage, false);
        stage.setTargetSprite(toSet);
    }
}