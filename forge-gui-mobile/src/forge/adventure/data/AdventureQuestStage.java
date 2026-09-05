package forge.adventure.data;

import forge.adventure.character.EnemySprite;
import forge.adventure.pointofintrest.PointOfInterest;
import forge.adventure.scene.TileMapScene;
import forge.adventure.stage.MapStage;
import forge.adventure.util.AdventureQuestController;
import forge.adventure.util.AdventureQuestEvent;
import forge.adventure.util.AdventureQuestEventType;
import forge.adventure.util.Current;
import forge.adventure.util.DungeonRotation;
import forge.util.Aggregates;

import java.io.Serializable;
import java.util.*;
import java.util.stream.Collectors;

import static forge.adventure.util.AdventureQuestController.ObjectiveTypes.*;
import static forge.adventure.util.AdventureQuestController.QuestStatus.*;

public class AdventureQuestStage implements Serializable {

    private static final long serialVersionUID = 12042023L;

    public int id;
    private AdventureQuestController.QuestStatus status = INACTIVE;
    public String name = "";
    public String description = "";
    public boolean anyPOI = false; //false: Pick one PoI. True: Any PoI matching tags is usable
    public String mapFlag; //Map (or quest) flag to check
    public int mapFlagValue; //Minimum value for the flag
    public int count1; //use defined by objective type, this can be enemies to defeat, minimum PoI distance, etc
    public int count2; //use defined by objective type, this can be enemies to defeat, minimum PoI distance, etc
    public int count3; //use defined by objective type, this can be enemies to defeat, minimum PoI distance, etc
    public int count4; //use defined by objective type, this can be enemies to defeat, minimum PoI distance, etc
    private int progress1; //Progress toward count1
    private int progress2; //Progress toward count2
    private int progress3; //Progress toward count3
    private int progress4; //Progress toward count3
    public boolean mixedEnemies; //false: Pick one enemy type. True: Combine all potential types
    public boolean here; //Default PoI selection to current location
    private PointOfInterest targetPOI; //Destination. Expand to array to cover "anyPOI?"
    private transient EnemySprite targetSprite; //EnemySprite targeted by this quest stage.
    private EnemyData targetEnemyData; //Valid enemy type for this quest stage when mixedEnemies is false.
    public List<String> POITags = new ArrayList<>(); //Tags defining potential targets
    public boolean worldMapOK = false; //Accept progress toward this objective outside any POI
    public AdventureQuestController.ObjectiveTypes objective;
    public List<Integer> prerequisiteIDs = new ArrayList<>();
    public List<String> enemyTags = new ArrayList<>(); //Tags defining potential targets
    public List<String> enemyExcludeTags = new ArrayList<>(); //Tags denoting invalid targets
    // Native color-based Defeat filters (2026-08-29, faction-quest round). questTags-based color
    // matching (BiomeX/IdentityX) only reliably covers a small hand-curated slice of the enemy
    // catalog (verified: IdentityX tracks card-flavor color, not the enemy's own EnemyData.colors,
    // and BiomeX only tags ~22% of enemies) - these read the real, ~98%-populated colors field
    // directly instead. At most one of the two should be set per stage; deliberately excluded from
    // AdventureQuestController.getExtraQuestSpawns()'s tag-scan (see that method's comment) so a
    // stage using either of these never falls into "empty enemyTags matches the whole catalog."
    public String enemyColorLetter; // single MTG color letter (W/U/B/R/G) - matches ANY enemy whose EnemyData.colors contains it
    public String territoryMageColor; // lowercase color name (e.g. "black") - matches ONLY a live dispatched attack mage (EnemySprite.territoryColor) of that color, never an ordinary roamer
    public List<String> itemNames = new ArrayList<>(); //Tags defining items to use
    public List<String> equipNames = new ArrayList<>(); //Tags defining equipment to use
    public boolean prologueDisplayed = false;
    public boolean epilogueDisplayed = false;
    public DialogData prologue;
    public DialogData epilogue;
    public DialogData failureDialog;
    public String deliveryItem = ""; //Imaginary item to get/fetch/deliver. Could be a general purpose field.
    public String POIToken; //If defined, ignore tags input and use the target POI from a different stage's objective instead.
    private transient List<Integer> _parsedPrerequisiteNames;
    private transient List<PointOfInterest> validPOIs;
    public boolean allowInactivePOI = false;
    // Navigation-only town filter for the world-map quest arrow ("ruinedTown"/"survivingTown",
    // 2026-08-27 user request) - see getNavPOIs() for why this exists separately from validPOIs.
    public String navPOIFilter = "";

    public UUID stageID;

    public void initialize() {
        if (stageID == null) {
            stageID = UUID.randomUUID();
        }
        validPOIs = Current.world().getAllPointOfInterest();
    }

    public void checkPrerequisites(List<Integer> completedStages) {
        if (status != INACTIVE)
            return;
        for (Integer prereqID : prerequisiteIDs) {
            if (!completedStages.contains(prereqID)) {
                return;
            }
        }
        status = ACTIVE;
    }

    public AdventureQuestController.QuestStatus getStatus() {
        return status;
    }

    public PointOfInterest getTargetPOI() {
        return targetPOI;
    }

    public void setTargetPOI(PointOfInterest target) {
        if (!anyPOI)
            targetPOI = target;
    }

    public void setTargetPOI(Dictionary<String, PointOfInterest> poiTokens, String questName) {
        if (worldMapOK)
            return;
        if (POIToken != null && !POIToken.isEmpty()) {
            PointOfInterest tokenTarget = poiTokens.get(POIToken);
            if (tokenTarget != null) {
                setTargetPOI(tokenTarget);
                return;
            } else {
                System.out.println("Quest '" + questName + "' -  Stage '" + this.name + "' failed to generate POI from token reference: '" + POIToken + "'");
            }
        }
        if (here) {
            setTargetPOI(AdventureQuestController.instance().mostRecentPOI);
            return;
        }
        // Tag-filter FIRST, then the active-filter (2026-08-16 reorder - see below): both filters
        // still apply on the normal path, but the tag-matched-yet-inactive pool must survive as a
        // fallback rather than being discarded before tags are even checked.
        for (String tag : POITags) {
            validPOIs.removeIf(q -> Arrays.stream(q.getData().questTags).noneMatch(tag::equals));
        }
        if (!allowInactivePOI) {
            List<PointOfInterest> activeMatched = new ArrayList<>(validPOIs);
            activeMatched.removeIf(q -> !q.getActive()); //inactive POIs do not appear on map until conditions are met to activate them
            if (!activeMatched.isEmpty() || anyPOI) {
                validPOIs = activeMatched;
            }
            // else: every tag-matching POI is currently an inactive dungeon-rotation reserve slot
            // (only DungeonRotation ever deactivates a POI). Keep the inactive pool - the pick
            // below will bind one and DungeonRotation.onQuestTargetBound() force-spawns it, so
            // the quest ALWAYS points at a real, enterable location (2026-08-16 user spec:
            // "confirm the dungeon actually exists... if not, we need to spawn that into
            // existence"). The old behavior silently left targetPOI null here - the stage could
            // never complete and the offer text showed a raw "$(poi_1)" token.
        }
        if (!anyPOI) {
            if (validPOIs.isEmpty()) {
                //no POI matched, fall back to anyPOI valid for the objective that doesn't match all tags
                validPOIs = Current.world().getAllPointOfInterest();
                return;
            }
            int targetIndex = (count1 * validPOIs.size() / 100);
            int variance = (count2 * validPOIs.size()) / 100;
            targetIndex = Math.max(0, (int) (targetIndex - variance + (new Random().nextFloat() * variance * 2)));

            if (targetIndex < validPOIs.size() && targetIndex >= 0) {
                validPOIs.sort(new AdventureQuestController.DistanceSort());
                setTargetPOI(validPOIs.get(targetIndex));
            } else {
                if (count1 != 0 || count2 != 0) {
                    System.out.println("Quest '" + questName + "' -  Stage '" + this.name + "' has invalid count1 ('" + count1 + "') and/or count2 ('" + count2 + "') value");
                }
                setTargetPOI(Aggregates.random(validPOIs));
            }
            // Existence guarantee + timer runway (2026-08-16 user spec): force-spawn the target
            // if it was picked from the inactive reserve, and either way push a rotatable
            // target's despawn day out 30 days so a freshly-given quest can't have its dungeon
            // rotate away mid-quest. No-op for towns/story dungeons/non-rotation planes.
            if (targetPOI != null)
                DungeonRotation.onQuestTargetBound(targetPOI);
        }
        //"else" any POI matching all the POITags is valid, evaluate as needed
    }

    public EnemySprite getTargetSprite() {
        return targetSprite;
    }

    public void setTargetEnemyData(EnemyData target) {
        targetEnemyData = target;
    }

    public EnemyData getTargetEnemyData() {
        if (targetEnemyData == null && targetSprite != null)
            return targetSprite.getData();
        return targetEnemyData;
    }

    public void setTargetSprite(EnemySprite target) {
        targetSprite = target;
    }

    public boolean checkIfTargetLocation() {
        return checkIfTargetLocation(TileMapScene.instance().rootPoint);
    }

    public boolean checkIfTargetLocation(PointOfInterest locationToCheck) {
        if (!MapStage.getInstance().isInMap())
        {
            return worldMapOK;
        }
        if (targetPOI == null) {
            List<String> enteredTags = Arrays.stream(locationToCheck.getData().questTags).collect(Collectors.toList());
            for (String tag : POITags) {
                if (!enteredTags.contains(tag)) {
                    return false;
                }
            }
        }
        if (targetPOI != null) {
            return targetPOI.getPosition().equals(locationToCheck.getPosition());
        }
        return anyPOI;
    }

    public boolean checkIfTargetEnemy(EnemySprite enemy) {
        // Native color filters (see field comments) take priority and are mutually exclusive
        // with the questTags path below - both new faction-quest stage kinds set neither
        // targetEnemyData nor enemyTags, so falling through would otherwise degenerate into
        // "any enemy at all" (empty enemyTags matches everything in the loop below).
        if (territoryMageColor != null && !territoryMageColor.isEmpty()) {
            return territoryMageColor.equalsIgnoreCase(enemy.territoryColor);
        }
        if (enemyColorLetter != null && !enemyColorLetter.isEmpty()) {
            String colors = enemy.getData().colors;
            return colors != null && colors.contains(enemyColorLetter);
        }
        if (targetEnemyData != null) {
            return (enemy.getData().match(targetEnemyData));
        }
        else if (targetSprite == null) {
            ArrayList<String> candidateTags = new ArrayList<>(Arrays.asList(enemy.getData().questTags));
            int tagCount = candidateTags.size();

            candidateTags.removeAll(enemyExcludeTags);
            if (candidateTags.size() != tagCount) {
                return false;
            }

            candidateTags.removeAll(enemyTags);
            return candidateTags.size() == tagCount - enemyTags.size();
        } else  {
            return targetSprite.equals(enemy);
        }
    }

    public AdventureQuestStage() {

    }

    public AdventureQuestStage(AdventureQuestStage other) {
        this.status = other.status;
        this.prologueDisplayed = other.prologueDisplayed;
        this.prologue = new DialogData(other.prologue);
        this.epilogueDisplayed = other.epilogueDisplayed;
        this.epilogue = new DialogData(other.epilogue);
        this.failureDialog = new DialogData(other.failureDialog);
        this.name = other.name;
        this.description = other.description;
        this.progress1 = other.progress1;
        this.progress2 = other.progress2;
        this.progress3 = other.progress3;
        this.count1 = other.count1;
        this.count2 = other.count2;
        this.count3 = other.count3;
        this.count4 = other.count4;
        this.enemyTags = other.enemyTags;
        this.enemyExcludeTags = other.enemyExcludeTags;
        this.anyPOI = other.anyPOI;
        this.here = other.here;
        this.targetPOI = other.targetPOI;
        this.objective = other.objective;
        this.mapFlagValue = other.mapFlagValue;
        this.mapFlag = other.mapFlag;
        this.equipNames = other.equipNames;
        this.mixedEnemies = other.mixedEnemies;
        this.itemNames = other.itemNames;
        this.prerequisiteIDs = other.prerequisiteIDs;
        this.POIToken = other.POIToken;
        this.id = other.id;
        this.POITags = other.POITags;
        this.targetEnemyData = other.targetEnemyData;
        this.deliveryItem = other.deliveryItem;
        this.worldMapOK = other.worldMapOK;
        this.allowInactivePOI = other.allowInactivePOI;
        this.navPOIFilter = other.navPOIFilter;
        this.enemyColorLetter = other.enemyColorLetter;
        this.territoryMageColor = other.territoryMageColor;
    }


    public List<PointOfInterest> getValidPOIs() {
        if (worldMapOK)
            return new ArrayList<>();
        if (objective == Hunt)
            return new ArrayList<>();
        if (validPOIs == null)
            validPOIs = new ArrayList<>();
        if (validPOIs.size() != 1 && targetPOI != null) {
            validPOIs.clear();
            validPOIs.add(targetPOI);
        }
        if (validPOIs.isEmpty() && targetPOI == null && !POITags.isEmpty())
        {
            validPOIs = Current.world().getAllPointOfInterest();
            if (!allowInactivePOI) {
                validPOIs.removeIf(q -> !q.getActive()); //inactive POIs do not appear on map until conditions are met to activate them
            }
            for (String tag : POITags) {
                validPOIs.removeIf(q -> Arrays.stream(q.getData().questTags).noneMatch(tag::equals));
            }
        }
        return validPOIs;
    }

    /** Navigation-only POI pool for the world-map quest arrow (2026-08-27 user request: the
     *  "Find a ruined town"/"Find a surviving town" tutorial stages should guide the player the
     *  way "find a dungeon" does). Those stages are worldMapOK character-flag stages, which
     *  getValidPOIs() correctly hides from the arrow - and worldMapOK is load-bearing for their
     *  completion (the CHARACTERFLAG event carries no POI), so it cannot be dropped. Instead
     *  quests.json sets navPOIFilter on such a stage and the arrow calls this accessor; nothing
     *  in completion logic (handleEvent/checkIfTargetLocation) reads this pool, by construction.
     *  Recomputed per call: ruined-vs-surviving status changes at runtime (towns get restored),
     *  and the peek lookups are plain hashmap gets - cheap enough per frame. */
    public List<PointOfInterest> getNavPOIs() {
        if (navPOIFilter == null || navPOIFilter.isEmpty())
            return getValidPOIs();
        List<PointOfInterest> pool = Current.world().getAllPointOfInterest();
        pool.removeIf(q -> !q.getActive());
        for (String tag : POITags) {
            pool.removeIf(q -> Arrays.stream(q.getData().questTags).noneMatch(tag::equals));
        }
        pool.removeIf(q -> !matchesNavFilter(q));
        return pool;
    }

    private boolean matchesNavFilter(PointOfInterest poi) {
        try {
            // peek, never get: getPointOfInterestChanges() is get-or-create and would grow the
            // save with an empty entry for every town the arrow ever considered.
            forge.adventure.pointofintrest.PointOfInterestChanges changes =
                    forge.adventure.world.WorldSave.getCurrentSave().peekPointOfInterestChanges(poi.getID());
            switch (navPOIFilter) {
                case "ruinedTown":
                    return forge.adventure.util.TownRestoration.isWastelandTown(poi.getData())
                            && !forge.adventure.util.TownRestoration.isNeutralSeededTown(changes)
                            && !forge.adventure.util.TownRestoration.isTownRestored(changes)
                            && !forge.adventure.util.TerritoryControl.isRingTown(poi); // round 115: never a Ring City
                case "survivingTown":
                    return forge.adventure.util.TownRestoration.isNeutralSeededTown(changes)
                            && !forge.adventure.util.TerritoryControl.isRingTown(poi); // round 115: an independent town, not one of the five Ring Cities
                case "restoredTown":
                    return forge.adventure.util.TownRestoration.isTownRestored(changes);
                case "tagged": // round 102: any POI that carries the stage's POITags (the Ring City stages of quest 75)
                    return true;
                default:
                    return false;
            }
        } catch (Exception e) {
            return false;
        }
    }

    public AdventureQuestController.QuestStatus handleEvent(AdventureQuestEvent event) {
        if (objective == Fetch && hasRequiredFetchItems()) {
            status = COMPLETE;
            return status;
        }

        if (!checkIfTargetLocation(event.poi))
            return status;

        if (event.enemy != null && !checkIfTargetEnemy(event.enemy))
            return status;

        switch (objective) {
            case CharacterFlag:
                if (event.type == AdventureQuestEventType.CHARACTERFLAG)
                    status = event.flagName != null && event.flagName.equals(this.mapFlag) && event.flagValue >= this.mapFlagValue ? COMPLETE : status;
                break;
            case CompleteQuest:
                status = event.type == AdventureQuestEventType.QUESTCOMPLETE
                        && (anyPOI || event.otherQuest != null && event.otherQuest.sourceID.equals(targetPOI.getID()))
                        && ++progress3 >= count3 ? COMPLETE : status;
                break;
            case Clear:
                if (event.clear && event.winner) {
                    status = COMPLETE;
                }
                break;
            case Defeat:
                if (event.type != AdventureQuestEventType.MATCHCOMPLETE)
                    break;
                if (event.winner) {
                    status = ++progress3 >= count3 ? COMPLETE : status;
                } else {
                    status = ++progress4 >= count4 && count4 > 0 ? FAILED : status;
                }
                break;
            case Arena:
                status = event.type == AdventureQuestEventType.ARENACOMPLETE
                        && event.winner //if event won & not conceded
                        && ++progress3 >= count3 ? COMPLETE : status;
                break;
            case EventFinish:
                if (event.type != AdventureQuestEventType.EVENTCOMPLETE)
                    break;
                status = ++progress3 >= count3 ? COMPLETE : status;
                break;
            case EventWin:
                if (event.type != AdventureQuestEventType.EVENTCOMPLETE)
                    break;
                if (event.winner) {
                    status = ++progress3 >= count3 ? COMPLETE : status;
                } else {
                    status = ++progress4 >= count4 && count4 > 0 ? FAILED : status;
                }
                break;
            case EventWinMatches:
                if (event.type != AdventureQuestEventType.EVENTMATCHCOMPLETE)
                    break;
                if (event.winner) {
                    status = ++progress3 >= count3 ? COMPLETE : status;
                } else {
                    status = ++progress4 >= count4 && count4 > 0 ? FAILED : status;
                }
                break;
            case Fetch:
                if (event.type == AdventureQuestEventType.RECEIVEITEM) {
                    if ((itemNames.isEmpty()) || (event.item != null && itemNames.contains(event.item.name)))
                        status = ++progress1 >= count1 ? COMPLETE : status;
                }
                else if (event.type == AdventureQuestEventType.USEITEM) {
                    if ((itemNames.isEmpty()) || (event.item != null && itemNames.contains(event.item.name)))
                        status = ++progress3 >= count3 ? COMPLETE : status;
                }
                break;
            case Hunt:
                if (event.type == AdventureQuestEventType.DESPAWN) {
                    status = event.enemy.equals(targetSprite) ? FAILED : status;
                } else if (event.type == AdventureQuestEventType.MATCHCOMPLETE) {
                    if (event.winner) {
                        status = event.enemy.equals(targetSprite) ? COMPLETE : status;
                    } else {
                        status = ++progress4 >= count4 && count4 > 0 ? FAILED : status;
                    }
                }
                break;
            case Leave:
                if (event.type == AdventureQuestEventType.LEAVEPOI)
                    status = ++progress3 >= count3 ? COMPLETE : status;
                break;
            case MapFlag:
                if (event.type == AdventureQuestEventType.MAPFLAG)
                    status = event.flagName != null &&  event.flagName.equals(this.mapFlag) && event.flagValue >= this.mapFlagValue ? COMPLETE : status;
                break;
            case QuestFlag:
                if (event.type == AdventureQuestEventType.QUESTFLAG)
                    status = event.flagName != null &&  event.flagName.equals(this.mapFlag) && event.flagValue >= this.mapFlagValue ? COMPLETE : status;
                break;
            case HaveReputation:
                //presumed that WorldMapOK will be set on this type, as reputation will occasionally be updated remotely by quests
                if (event.type == AdventureQuestEventType.REPUTATION)
                    status = checkIfTargetLocation(event.poi) && event.count3 >= count3 ? COMPLETE : status;
                break;
            case HaveReputationInCurrentLocation:
                if (event.type == AdventureQuestEventType.ENTERPOI || event.type == AdventureQuestEventType.REPUTATION)
                    status = event.count3 >= count3 ? COMPLETE : status;
                break;
            case Delivery:
                //will eventually differentiate from Travel
            case Travel:
                status = ++progress3 >= count3 ? COMPLETE : status;
                break;
            case Use:
                status = event.type == AdventureQuestEventType.USEITEM
                        && (itemNames.isEmpty()) || itemNames.contains(event.item.name)
                        && ++progress3 >= count3 ? COMPLETE : status;
                break;
        }
        return status;
    }

    /** Retroactive flag-objective completion (2026-08-26 user request: "add safeguards if the
     *  player does something before a quest. Like builds a capitol, before the quest fires").
     *  Flag objectives normally complete only on the live flag EVENT - a flag set BEFORE the
     *  stage activated would otherwise never complete it (the event already fired, and the
     *  stateless crossing can't recur unless the player repeats the action). Called from
     *  AdventureQuestData.activateNextStages() the moment a stage turns ACTIVE - the same
     *  activation-time retro-check pattern Fetch already uses via hasRequiredFetchItems().
     *  Deliberately bypasses the location gate: this is a check of persisted STATE, not a live
     *  event, so where the player happens to be standing is irrelevant. Returns true if the
     *  stage was completed retroactively. */
    public boolean retroCompleteIfFlagSatisfied() {
        if (status != ACTIVE || mapFlag == null || mapFlag.isEmpty())
            return false;
        boolean satisfied = false;
        try {
            if (objective == CharacterFlag) {
                satisfied = Current.player().getCharacterFlag(mapFlag) >= mapFlagValue;
            } else if (objective == QuestFlag) {
                satisfied = Current.player().getQuestFlag(mapFlag) >= mapFlagValue;
            } else if (objective == MapFlag) {
                // Per-POI flags: check the bound target if there is one, otherwise (anyPOI
                // stages like "restore a town" / "build a Trader") scan every recorded POI's
                // flags - bounded by POIs the player has actually interacted with, and this
                // only runs once per stage activation.
                if (targetPOI != null) {
                    forge.adventure.pointofintrest.PointOfInterestChanges targetChanges =
                            forge.adventure.world.WorldSave.getCurrentSave().peekPointOfInterestChanges(targetPOI.getID());
                    satisfied = targetChanges != null
                            && targetChanges.getMapFlags().getOrDefault(mapFlag, (byte) 0) >= mapFlagValue;
                } else if (anyPOI) {
                    for (forge.adventure.pointofintrest.PointOfInterestChanges anyChanges
                            : forge.adventure.world.WorldSave.getCurrentSave().getAllPointOfInterestChanges()) {
                        if (anyChanges.getMapFlags().getOrDefault(mapFlag, (byte) 0) >= mapFlagValue) {
                            satisfied = true;
                            break;
                        }
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace(); // a safeguard must never break quest activation
        }
        if (satisfied) {
            status = COMPLETE;
            System.out.println("[TFR-MainQuest] stage \"" + name + "\" retro-completed on activation (flag "
                    + mapFlag + " already >= " + mapFlagValue + ")");
        }
        return satisfied;
    }

    public boolean hasRequiredFetchItems() {
        if (objective != Fetch || itemNames == null || itemNames.isEmpty()) {
            return false;
        }

        int owned = 0;
        for (String itemName : itemNames) {
            if (itemName == null || itemName.isEmpty()) {
                continue;
            }
            owned += Current.player().countItem(itemName);
            if (owned >= count1) {
                return true;
            }
        }
        return false;
    }
}
