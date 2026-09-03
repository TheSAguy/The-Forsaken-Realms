package forge.adventure.util;

import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.Json;
import com.badlogic.gdx.utils.JsonReader;
import com.badlogic.gdx.utils.JsonValue;
import com.badlogic.gdx.utils.Timer;
import forge.Forge;
import forge.adventure.character.EnemySprite;
import forge.adventure.data.*;
import forge.adventure.pointofintrest.PointOfInterest;
import forge.adventure.pointofintrest.PointOfInterestChanges;
import forge.adventure.scene.TileMapScene;
import forge.adventure.stage.GameStage;
import forge.adventure.stage.MapStage;
import forge.adventure.world.WorldSave;
import forge.util.Aggregates;
import forge.util.Localizer;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

import static forge.adventure.util.AdventureQuestController.QuestStatus.*;

public class AdventureQuestController implements Serializable {
    // Save compatibility: pinned 2026-09-03 (round 90) at the value derived from the v1.04 class shape so save compatibility no longer depends on the class not changing.
    private static final long serialVersionUID = 8650489637624890168L;


    public static void trackQuest(AdventureQuestData quest) {
        for (AdventureQuestData q: Current.player().getQuests()){
            q.isTracked = q.equals(quest);
        }
    }

    public List<EnemyData> getExtraQuestSpawns(float difficultyFactor){
        List<EnemyData> extraSpawns = new ArrayList<>();
        for (AdventureQuestData q : Current.player().getQuests()) {
            for (AdventureQuestStage c : q.stages) {
                if (c.getStatus().equals(ACTIVE) && c.objective.equals(ObjectiveTypes.Defeat)) {
                    if (c.getTargetEnemyData() != null) {
                        extraSpawns.add(c.getTargetEnemyData());
                        continue;
                    }
                    // Native color-filter stages (enemyColorLetter/territoryMageColor, see
                    // AdventureQuestStage) never reach the tag scan below: they set no enemyTags,
                    // and an empty enemyTags list matches EVERY catalog entry in that loop -
                    // exactly the "Syr Faren" bug class this method was already patched for once.
                    // These quests complete via ordinary encounters (color stages) or the
                    // territory-mage's own independent dispatch spawn (mage stages) - never via
                    // a forced extra spawn here.
                    if ((c.enemyColorLetter != null && !c.enemyColorLetter.isEmpty())
                            || (c.territoryMageColor != null && !c.territoryMageColor.isEmpty()))
                        continue;
                    List<EnemyData> tagMatches = new ArrayList<>();
                    for (EnemyData enemy : WorldData.getAllEnemies()) {
                        List<String> candidateTags = Arrays.stream(enemy.questTags).collect(Collectors.toList());
                        boolean match = true;
                        for (String targetTag : c.enemyTags) {
                            if (!candidateTags.contains(targetTag)) {
                                match = false;
                                break;
                            }
                        }
                        for (String targetTag : c.enemyExcludeTags) {
                            if (candidateTags.contains(targetTag)) {
                                match = false;
                                break;
                            }
                        }
                        if (match) {
                            tagMatches.add(enemy);
                        }
                    }
                    // Filtered since 2026-08-27 ("Syr Faren (Master)" caught the player on day
                    // 1): a mixedEnemies Defeat stage with empty enemyTags degenerated this scan
                    // into "uniform random over the whole 1520-entry catalog, every spawn tick" -
                    // 59% of which is spawnRate:0 legends. An empty filtered pool is fine:
                    // BiomeData's caller treats null/empty as "no extra spawn this tick" and the
                    // ordinary weighted roll proceeds alone.
                    extraSpawns.addAll(filterQuestSpawnPool(tagMatches, difficultyFactor));
                }
            }
        }
        return extraSpawns;
    }

    /** Shared sanity filter for quest-driven spawn pools (2026-08-27 playtest fix). Applies the
     *  gates every ordinary roaming-spawn path already honors: no bosses or spawnRate<=0
     *  "Legends" catalog entries (SpawnTierWeighting.isExempt), nothing above the player's
     *  current difficulty rank, content-filter-table exclusions, and the weekly spawn-tier
     *  bracket cap (same table TerritoryControl.clampDispatchTierToWeek uses; null owningColor
     *  = the all-zero NEUTRAL delta, i.e. the bracket base). Hand-authored quest targets
     *  (stage.targetEnemyData) deliberately do NOT come through here - quests may script any
     *  enemy they like; this only guards pools built by generic tag scans. */
    public static List<EnemyData> filterQuestSpawnPool(List<EnemyData> candidates, float difficultyFactor) {
        List<EnemyData> filtered = new ArrayList<>();
        forge.adventure.world.World world = WorldSave.getCurrentSave() == null ? null : WorldSave.getCurrentSave().getWorld();
        boolean weighting = SpawnTierWeighting.isEnabled() && world != null;
        int week = weighting ? SpawnTierWeighting.currentWeek(world) : 0;
        for (EnemyData enemy : candidates) {
            if (enemy == null || SpawnTierWeighting.isExempt(enemy))
                continue;
            if (enemy.difficulty > difficultyFactor)
                continue;
            if (!ContentFilterTables.isEnemyIncluded(enemy.getName()))
                continue;
            if (weighting && SpawnTierWeighting.targetTierWeight(enemy.tier, week, null) <= 0f)
                continue;
            filtered.add(enemy);
        }
        return filtered;
    }

    public Map<String, Float> getBoostedSpawns(List<EnemyData> localSpawns, float totalWeightToAssign) {
        Map<String,Float> boostedSpawns = new HashMap<>();
        for (AdventureQuestData q : Current.player().getQuests()){
            for (AdventureQuestStage c : q.stages){
                if (c.getStatus().equals(ACTIVE) && c.objective.equals(ObjectiveTypes.Defeat))
                {
                    List<String> toBoost = new ArrayList<>();
                    if (c.mixedEnemies){
                        for (EnemyData enemy : localSpawns){
                            List<String> candidateTags = Arrays.stream(enemy.questTags).collect(Collectors.toList());
                            boolean match = true;
                            for (String targetTag : c.enemyTags) {
                                if (!candidateTags.contains(targetTag)) {
                                    match = false;
                                    break;
                                }
                            }
                            for (String targetTag : c.enemyExcludeTags) {
                                if (candidateTags.contains(targetTag)) {
                                    match = false;
                                    break;
                                }
                            }
                            if (match) {
                                toBoost.add(enemy.getName());
                            }
                        }
                    }
                    else{
                        toBoost.add(c.getTargetEnemyData().getName());
                    }
                    if (!toBoost.isEmpty()) {
                        float value = totalWeightToAssign / toBoost.size();
                        for (String key : toBoost) {
                            boostedSpawns.merge(key, value, Float::sum);
                        }
                    }
                }
            }
        }
        return boostedSpawns;
    }

    public enum ObjectiveTypes{
        None,
        Arena,
        CharacterFlag,
        Clear,
        CompleteQuest,
        Defeat,
        Delivery,
        Escort,
        EventFinish,
        EventWin,
        EventWinMatches,
        Fetch,
        Find,
        Gather,
        Give,
        HaveReputation,
        HaveReputationInCurrentLocation,
        Hunt,
        MapFlag,
        Leave,
        Patrol,
        QuestFlag,
        Rescue,
        Siege,
        Travel,
        Use
    }

    public enum QuestStatus{
        NONE,
        INACTIVE,
        ACTIVE,
        COMPLETE,
        FAILED
    }
    private Map<String, Long> nextQuestDate = new HashMap<>();
    private int maximumSideQuests = 5; //todo: move to configuration file
    private transient MapDialog activeDialog = null;
    private transient Array<AdventureQuestData> allQuests = new Array<>();
    private final transient Array<AdventureQuestData> allSideQuests = new Array<>();
    private Queue<DialogData> dialogQueue = new LinkedList<>();
    private Map<String,Date> questAvailability = new HashMap<>();
    public PointOfInterest mostRecentPOI;
    private final List<EnemySprite> enemySpriteList= new ArrayList<>();
    private int nextQuestID = 0;
    public void showQuestDialogs(GameStage stage) {
        List<AdventureQuestData> finishedQuests = new ArrayList<>();
        for (AdventureQuestData quest : Current.player().getQuests()) {
            DialogData prologue = quest.getPrologue();
            if (prologue != null){
                dialogQueue.add(prologue);
            }
            for (AdventureQuestStage questStage : quest.stages)
            {
                if (questStage.getStatus() == INACTIVE)
                    continue;
                if (questStage.prologue != null && !questStage.prologueDisplayed){
                    questStage.prologueDisplayed = true;
                    dialogQueue.add(questStage.prologue);
                }

                if (questStage.getStatus() == FAILED && questStage.failureDialog != null){
                    dialogQueue.add(questStage.failureDialog);
                    continue;
                }

                if (questStage.getStatus() == COMPLETE && questStage.epilogue != null && !questStage.epilogueDisplayed){
                    questStage.epilogueDisplayed = true;
                    dialogQueue.add(questStage.epilogue);
                }
            }

            if (quest.failed){
                finishedQuests.add(quest);
                if (quest.failureDialog != null){
                    dialogQueue.add(quest.failureDialog);
                }
            }

            if (!quest.completed)
                continue;
            DialogData epilogue = quest.getEpilogue();
            if (epilogue != null){
                dialogQueue.add(epilogue);
            }
            finishedQuests.add(quest);
            updateQuestComplete(quest);
        }

        if (activeDialog == null && !dialogQueue.isEmpty()){
            displayNextDialog((MapStage) stage);
        }

        for (AdventureQuestData toRemove : finishedQuests) {
            if (!toRemove.failed && locationHasMoreQuests()){
                nextQuestDate.remove(toRemove.sourceID);
            }

            Current.player().removeQuest(toRemove);
            //Todo: Add quest to a separate "completed / failed" log?
        }
    }

    public boolean locationHasMoreQuests(){
        //intent: eventually stop providing quests for the day in a given town to encourage exploration
        //todo: make values configurable
        return new Random().nextFloat() <= 0.85f;
    }
    public void displayNextDialog(MapStage stage){
        if (dialogQueue.peek() == null) {
            activeDialog = null;
            return;
        }

        DialogData data = dialogQueue.remove();
        activeDialog = new MapDialog(data, stage, -1, null);
        if (data.options == null || data.options.length == 0) {
            activeDialog.setEffects(data.action);
            displayNextDialog(stage);
            return;
        }

        ChangeListener listen = new ChangeListener() {
            @Override
            public void changed(ChangeEvent changeEvent, Actor actor) {
                activeDialog = null;

                displayNextDialog(stage);
            }
        };

        activeDialog.addDialogCompleteListener(listen);

        Timer.schedule(new Timer.Task() {
            @Override
            public void run() {
                stage.showDialog();
                activeDialog.activate();
                // Seems weird that data would be null here, but not null up there. Are we changing these values inside activate?
                if (data.options == null || data.options.length == 0) {
                    displayNextDialog(stage);
                }
            }
        }, 0.25f);
    }

    public static class DistanceSort implements Comparator<PointOfInterest>
    {
        //ToDo: Make this more generic, compare PoI, mobs, random points, and player position
        // In process, perhaps adjust nav indicator based on distance to target
        //Sorts POI by distance from the player
        public int compare(PointOfInterest a, PointOfInterest b)
        {
            float distToA = new Vector2(a.getPosition()).sub(Current.player().getWorldPosX(), Current.player().getWorldPosY()).len();
            float distToB = new Vector2(b.getPosition()).sub(Current.player().getWorldPosX(), Current.player().getWorldPosY()).len();
            if (distToA - distToB < 0.0f)
                return -1;
            else if (distToA - distToB > 0.0f)
                return 1;
            return 0;
        }
    }
    private static AdventureQuestController object;

    public static AdventureQuestController instance() {
        if (object == null) {
            object = new AdventureQuestController();
            object.loadData();
        }
        return object;
    }

    public static void clear(){
        object = null;
    }

    public boolean hasClearQuestActive() {
        if (!MapStage.getInstance().isInMap() || TileMapScene.instance().rootPoint == null) {
            return false;
        }
        for (AdventureQuestData quest : Current.player().getQuests()) {
            for (AdventureQuestStage stage : quest.stages) {
                if (stage.getStatus() == ACTIVE
                        && stage.objective == ObjectiveTypes.Clear
                        && stage.checkIfTargetLocation(TileMapScene.instance().rootPoint)) {
                    return true;
                }
            }
        }
        return false;
    }

    private AdventureQuestController(){

    }

    public AdventureQuestController(AdventureQuestController other){
        if (object == null) {
            maximumSideQuests = other.maximumSideQuests;
            mostRecentPOI = other.mostRecentPOI;
            dialogQueue = other.dialogQueue;
            questAvailability = other.questAvailability;

            object = this;
            loadData();
        }
        else{
            System.out.println("Could not initialize AdventureQuestController. An instance already exists and cannot be merged.");
        }
    }

    // Low-probability quest offering (2026-08-29, faction-quest round), keyed by quest id -
    // deliberately kept OUT of AdventureQuestData as a real field (see that class's own note):
    // populated by a second, untyped pass over the same quests.json below, so it never touches
    // AdventureQuestData's java.io.Serializable shape (old-save compat) or requires libGDX's
    // typed Json.fromJson reflection to know about it (which - unrelated to Serializable -
    // independently refuses to populate transient fields too, the second bug this same day).
    // 0/absent = always eligible, matching every quest that predates this feature.
    private final Map<Integer, Float> questOfferProbability = new HashMap<>();

    private void loadData(){
        Json json = new Json();
        json.setIgnoreUnknownFields(true); // "offerProbability" in quests.json is read separately below, not as a class field
        FileHandle handle = Config.instance().getFile(Paths.QUESTS);
        if (handle.exists())
        {
            allQuests =json.fromJson(Array.class, AdventureQuestData.class, handle);
            JsonValue root = new JsonReader().parse(handle);
            for (JsonValue q = root.child; q != null; q = q.next) {
                float probability = q.getFloat("offerProbability", 0f);
                if (probability > 0f)
                    questOfferProbability.put(q.getInt("id"), probability);
            }
        }

        for (AdventureQuestData q : allQuests){
            if (q.storyQuest) continue;
            allSideQuests.add(q);
        }
    }

    public int getNextQuestID(){
        if (nextQuestID == 0 && allQuests.size > 0) {
            for (int i = 0; i < allQuests.size; i++) {
                if (allQuests.get(i).getID() >= nextQuestID){
                    nextQuestID = allQuests.get(i).getID() + 1;
                }
            }
        }
        return nextQuestID++;
    }

    public void activateNextStages() {
        for(AdventureQuestData currentQuest : Current.player().getQuests()) {
            currentQuest.activateNextStages();
        }
    }

    public void updateEnteredPOI(PointOfInterest arrivedAt)
    {
        AdventureQuestEvent event = new AdventureQuestEvent();
        event.type = AdventureQuestEventType.ENTERPOI;
        event.poi = arrivedAt;
        event.count3 = WorldSave.getCurrentSave().getPointOfInterestChanges(arrivedAt.getID()).getMapReputation();
        for(AdventureQuestData currentQuest : Current.player().getQuests()) {
            currentQuest.updateStages(event);
        }
        activateNextStages();
    }

    public void updateQuestsMapFlag(String updatedMapFlag, int updatedFlagValue)
    {
        AdventureQuestEvent event = new AdventureQuestEvent();
        event.type = AdventureQuestEventType.MAPFLAG;
        event.flagName = updatedMapFlag;
        event.flagValue = updatedFlagValue;
        for(AdventureQuestData currentQuest : Current.player().getQuests()) {
            currentQuest.updateStages(event);
        }
        activateNextStages();
    }

    public void updateQuestsCharacterFlag(String updatedCharacterFlag, int updatedCharacterFlagValue)
    {
        AdventureQuestEvent event = new AdventureQuestEvent();
        event.type = AdventureQuestEventType.CHARACTERFLAG;
        event.flagName = updatedCharacterFlag;
        event.flagValue = updatedCharacterFlagValue;
        for(AdventureQuestData currentQuest : Current.player().getQuests()) {
            currentQuest.updateStages(event);
        }
        activateNextStages();
    }

    public void updateQuestsQuestFlag(String updatedQuestFlag, int updatedQuestFlagValue)
    {
        AdventureQuestEvent event = new AdventureQuestEvent();
        event.type = AdventureQuestEventType.QUESTFLAG;
        event.flagName = updatedQuestFlag;
        event.flagValue = updatedQuestFlagValue;
        for(AdventureQuestData currentQuest : Current.player().getQuests()) {
            currentQuest.updateStages(event);
        }
        activateNextStages();
    }

    public void updateQuestsLeave(){
        AdventureQuestEvent event = new AdventureQuestEvent();
        event.type = AdventureQuestEventType.LEAVEPOI;
        for(AdventureQuestData currentQuest : Current.player().getQuests()) {
            currentQuest.updateStages(event);
        }
        activateNextStages();
    }

    public void updateQuestsWin(EnemySprite defeated, ArrayList<EnemySprite> enemies){
        enemySpriteList.remove(defeated);
        boolean allEnemiesCleared = true;
        if (enemies != null) {
            //battle was won in a dungeon, check for "clear" objectives
            for (EnemySprite enemy : enemies) {
                if (enemy.getStage() != null && !enemy.equals(defeated)) {
                    //actor is an enemy that is present on the map. Check to see if there's a valid reason.
                    if (enemy.defeatDialog != null) {
                        //This enemy cannot be removed from the map by defeating it, ignore it for "cleared" purposes
                        continue;
                    }
                    allEnemiesCleared = false;
                    break;
                }
            }
            // Dungeon-clear despawn (2026-08-18 user request: "Silly to have an empty dungeon
            // on the map... we should have it de-spawn, to make room for new dungeons").
            // Mirrors the existing DungeonRotation.onDungeonDefeat() call on the loss side -
            // no-op for non-rotatable POIs (story dungeons, bosses, towns) and on planes
            // without rotation, see that method's own gating. Nested inside this
            // enemies != null branch specifically (not a bare allEnemiesCleared check) since
            // that's this method's own "this really was a dungeon-context battle" signal -
            // the single-enemy overworld-duel overload below passes enemies=null and would
            // otherwise trip allEnemiesCleared's true-by-default value for every ordinary win.
            if (allEnemiesCleared)
                DungeonRotation.onDungeonClear(TileMapScene.instance().rootPoint);
        }
        AdventureQuestEvent event = new AdventureQuestEvent();
        event.type = AdventureQuestEventType.MATCHCOMPLETE;
        event.winner = true;
        event.enemy = defeated;
        event.clear = allEnemiesCleared;
        for(AdventureQuestData currentQuest : Current.player().getQuests()) {
            currentQuest.updateStages(event);
        }
        activateNextStages();
    }
    public void updateQuestsWin(EnemySprite defeated){
        updateQuestsWin(defeated, null);
    }

    public void updateQuestsLose(EnemySprite defeatedBy){
        enemySpriteList.remove(defeatedBy);
        AdventureQuestEvent event = new AdventureQuestEvent();
        event.type = AdventureQuestEventType.MATCHCOMPLETE;
        event.winner = false;
        event.enemy = defeatedBy;
        event.clear = false;
        for(AdventureQuestData currentQuest : Current.player().getQuests()) {
            currentQuest.updateStages(event);
        }
        activateNextStages();
    }

    public void updateDespawn(EnemySprite despawned){
        enemySpriteList.remove(despawned);
        AdventureQuestEvent event = new AdventureQuestEvent();
        event.type = AdventureQuestEventType.DESPAWN;
        event.enemy = despawned;
        for(AdventureQuestData currentQuest : Current.player().getQuests()) {
            currentQuest.updateStages(event);
        }
        activateNextStages();
    }

    public void updateArenaComplete(boolean winner){
        AdventureQuestEvent event = new AdventureQuestEvent();
        event.type = AdventureQuestEventType.ARENACOMPLETE;
        event.winner = winner;
        for(AdventureQuestData currentQuest : Current.player().getQuests()) {
            currentQuest.updateStages(event);
        }
        activateNextStages();
    }

    public void updateEventComplete(AdventureEventData completedEvent) {
        AdventureQuestEvent event = new AdventureQuestEvent();
        event.type = AdventureQuestEventType.EVENTCOMPLETE;
        event.winner = completedEvent.playerWon;
        for(AdventureQuestData currentQuest : Current.player().getQuests()) {
            currentQuest.updateStages(event);
        }
        activateNextStages();
    }

    public void updateQuestComplete(AdventureQuestData completedQuest) {
        AdventureQuestEvent event = new AdventureQuestEvent();
        event.type = AdventureQuestEventType.QUESTCOMPLETE;
        event.otherQuest = completedQuest;
        for(AdventureQuestData currentQuest : Current.player().getQuests()) {
            currentQuest.updateStages(event);
        }
        activateNextStages();
    }

    public void updateItemUsed(ItemData data) {
        AdventureQuestEvent event = new AdventureQuestEvent();
        event.type = AdventureQuestEventType.USEITEM;
        event.item = data;
        for(AdventureQuestData currentQuest : Current.player().getQuests()) {
            currentQuest.updateStages(event);
        }
        activateNextStages();
    }

    public void updateItemReceived(ItemData data) {
        AdventureQuestEvent event = new AdventureQuestEvent();
        event.type = AdventureQuestEventType.RECEIVEITEM;
        event.item = data;

        for(AdventureQuestData currentQuest : Current.player().getQuests()) {
            currentQuest.updateStages(event);
        }
        activateNextStages();
    }

    public AdventureQuestData generateQuest(int id){
        AdventureQuestData generated = null;
        for (AdventureQuestData template: allQuests) {
            if (template.isTemplate && template.getID() == id){
                generated = new AdventureQuestData(template);
                generated.initialize();
                break;
            }
        }
        return generated;
    }

    public void addQuestSprites(AdventureQuestStage stage){
        if (stage.getTargetSprite() != null){
            enemySpriteList.add(stage.getTargetSprite());
        }
    }
    public List<EnemySprite> getQuestSprites(){
        return enemySpriteList;
    }

    public void rematchQuestSprite(EnemySprite sprite){
        for (AdventureQuestData q : Current.player().getQuests()){
            for (AdventureQuestStage s : q.stages){
                if (sprite.questStageID != null && s.stageID != null && sprite.questStageID.equals(s.stageID.toString())) {
                    s.setTargetSprite(sprite);
                }
            }
        }
    }

    String randomItemName()
    {  //todo: expand and include in fetch/delivery quests
        Localizer localizer = Forge.getLocalizer();
        String[] options = {
                localizer.getMessage("advRewardItem1"),
                localizer.getMessage("advRewardItem2"),
                localizer.getMessage("advRewardItem3"),
                localizer.getMessage("advRewardItem4"),
                localizer.getMessage("advRewardItem5"),
                localizer.getMessage("advRewardItem6"),
                localizer.getMessage("advRewardItem7"),
                localizer.getMessage("advRewardItem8"),
                localizer.getMessage("advRewardItem9"),
                localizer.getMessage("advRewardItem10")};

        return Aggregates.random(options);
    }

    public void abandon(AdventureQuestData quest){
        quest.fail();
    }

    public AdventureQuestData getQuestNPCResponse(String pointID, PointOfInterestChanges changes, String questOrigin) {
        Localizer localizer = Forge.getLocalizer();
        AdventureQuestData ret;

        for (AdventureQuestData q : Current.player().getQuests()) {
            if (q.completed || q.storyQuest)
                continue;
            if (q.sourceID.equals(pointID)) {
                //remind player about current active side quest
                DialogData response = new DialogData();
                response.text = localizer.getMessage("advQuestNotFinished", q.name);
                DialogData dismiss = new DialogData();
                dismiss.name = localizer.getMessage("advQuestGoTakeCareOfThat");
                response.options = new DialogData[]{dismiss};
                ret = new AdventureQuestData();
                ret.offerDialog = response;
                return ret;
            }
        }
        if (nextQuestDate.containsKey(pointID) && nextQuestDate.get(pointID) >= LocalDate.now().toEpochDay()){
            //No more side quests available here today due to previous activity
            DialogData response = new DialogData();
            response.text = localizer.getMessage("advQuestComeBackTomorrow");
            DialogData dismiss = new DialogData();
            dismiss.name = localizer.getMessage("advOkayLeave");
            response.options = new DialogData[]{dismiss};
            ret = new AdventureQuestData();
            ret.offerDialog = response;
            return ret;
        }

        if (tooManyQuests(Current.player().getQuests())) {
            //No more side quests available here today, too many active
            DialogData response = new DialogData();
            response.text = localizer.getMessage("advQuestNeedAssistance");
            DialogData dismiss = new DialogData();
            dismiss.name = localizer.getMessage("advQuestLogFull");
            response.options = new DialogData[]{dismiss};
            ret = new AdventureQuestData();
            ret.offerDialog = response;
            return ret;
        }
        nextQuestDate.put(pointID, LocalDate.now().toEpochDay());

        // Availability is uniform random among tag-matching candidates (Aggregates.random below),
        // EXCEPT low-probability quests (offerProbability > 0, 2026-08-29 faction-quest round)
        // get an extra Bernoulli gate before they're even added to that pool - so they show up
        // rarely without permanently disappearing, and every ordinary quest (offerProbability
        // still 0) keeps its exact prior behavior.
        Array<AdventureQuestData> validSideQuests = new Array<>();
        for (AdventureQuestData option : allSideQuests){
            boolean tagMatch = option.questSourceTags.length == 0;
            if (!tagMatch) {
                for (int i = 0; i < option.questSourceTags.length; i++){
                    if (option.questSourceTags[i] != null && option.questSourceTags[i].equals(questOrigin)){
                        tagMatch = true;
                        break;
                    }
                }
            }
            if (!tagMatch)
                continue;
            float offerProbability = questOfferProbability.getOrDefault(option.getID(), 0f);
            if (offerProbability > 0f && new Random().nextFloat() > offerProbability)
                continue;
            validSideQuests.add(option);
        }
        if (validSideQuests.size > 0)
            ret = new AdventureQuestData(Aggregates.random(validSideQuests));
        else
            ret = new AdventureQuestData(Aggregates.random(allSideQuests));
        ret.sourceID = pointID;
        ret.initialize();
        return ret;
    }

    private boolean tooManyQuests(List<AdventureQuestData> existing){
        int sideQuests = 0;

        for (AdventureQuestData quest : existing){
            if (quest.storyQuest || quest.completed || quest.failed)
                continue;
            sideQuests++;
        }
        return (sideQuests >= maximumSideQuests);
    }
}
