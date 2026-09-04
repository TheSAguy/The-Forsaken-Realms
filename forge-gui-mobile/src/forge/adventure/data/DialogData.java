package forge.adventure.data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Dialog Data JSON loader class.
 * Carries all text, branches and effects of dialogs.
 */
public class DialogData implements Serializable {
    private static final long serialVersionUID = 1L;

    public ActionData[] action = new ActionData[0];       //List of effects to cause when the dialog shows.
    public ConditionData[] condition = new ConditionData[0]; //List of conditions for the action to show.
    public String name = "";               //Text to display when action is listed as a button.
    public String locname = "";            //References a localized string for the button labels.
    public String text = "";               //The text body.
    public String loctext= "";            //References a localized string for the text body.
    public DialogData[] options = new DialogData[0];      //List of sub-dialogs. Show up as options in the current one.
    public boolean isDisabled = false;
    // Keep the LAST option out of the scrollable area when this dialog's option list is long
    // enough to scroll (mod addition 2026-08-30, user report on the shop chooser: "There was no
    // back/cancel option, I had to build a shop" - the Back button was simply below the scroll
    // fold). Set on menus whose final entry is an escape hatch (Back / Not now); leave false for
    // quest dialogs, where the last option is a real choice and belongs in the list with the rest.
    public boolean pinLastOption = false;

    public transient Consumer callback;

    public DialogData(){}
    public DialogData(DialogData other){
        if (other == null)
            return;

        this.action = other.action.clone();
        this.condition = other.condition.clone();
        this.name = other.name;
        this.locname = other.locname.isEmpty()?"":("Copy of " + other.locname);
        this.text = other.text;
        this.loctext = other.loctext;
        List<DialogData> clonedOptions = new ArrayList<>();
        for (DialogData option: other.options){
            clonedOptions.add(new DialogData(option));
        }
        this.options = clonedOptions.toArray(new DialogData[0]);
        this.voiceFile = other.voiceFile;
        this.isDisabled = other.isDisabled;
    }

    @Override
    public String toString(){
        return this.name;
    }

    public String voiceFile;

    static public class ActionData implements Serializable {
        public static final long serialVersionUID = 2848523275822677205L;
        static public class QuestFlag implements Serializable{
            public String key;
            public int val;
        }
        public String removeItem;         //Remove item name from inventory.
        public String addItem;            //Add item name to inventory.
        public int addLife = 0;           //Gives the player X health. Negative to take.
        public int addGold = 0;           //Gives the player X gold. Negative to take.
        public int addShards = 0;           //Gives the player X shards. Negative to take.
        public int addWood = 0;           //Gives the player X wood. Negative to take. (mod: multi-resource building costs)
        public String grantRingGift;      //mod round 101: "gold"|"shards"|"wood"|"stone"|"items"|"all" - the difficulty's starting amounts, handed over by the Ring Cities.
        public int addStone = 0;          //Gives the player X stone. Negative to take. (mod)

        public int deleteMapObject = 0;   //Remove ID from the map. -1 for self.
        public int activateMapObject = 0; //Remove inactive state from ID.
        public int battleWithActorID = 0; //Start a battle with enemy ID. -1 for self if possible.
        public EffectData giveBlessing;   //Give a blessing to the player.
        public String setColorIdentity;   //Change player's color identity.
        public String advanceCharacterFlag;   //Increase given quest flag by 1.
        public String advanceQuestFlag;   //Increase given quest flag by 1.
        public String advanceMapFlag;     //Increase given map flag by 1.
        public EffectData setEffect;      //Set or replace current effects on current actor.
        public QuestFlag setCharacterFlag;    //Set quest flag.
        public QuestFlag setQuestFlag;    //Set quest flag.
        public QuestFlag setMapFlag;      //Set map flag.

        public RewardData[] grantRewards = new RewardData[0];   //launch a RewardScene with the provided data.
        public RewardData[] grantRewardsChoice = new RewardData[0];   //launch a RewardScene choice with the provided data.
        public String issueQuest; //Add quest with this ID to the player's questlog.

        public int addMapReputation = 0;  //Gives the player X reputation points in this POI. Negative to take.
        public String POIReference; //used with addMapReputation when a quest step affects reputation in another location

        // Direct, non-zero-sum grant to an AI color's reputation score (2026-08-29, faction-quest
        // round) - distinct from addMapReputation above, which is a single POI's own local
        // reputation number, not this ColorReputation/colorReputationHalfPoints system. Mirrors
        // the existing direct-grant precedent ColorReputation.applyColorDefeatPenalty() already
        // uses (no 5-color wheel redistribution, just add to the one color). Amount is in DISPLAY
        // points (matches what the player sees in the reputation UI), doubled internally to the
        // half-point storage unit by AdventurePlayer.addColorReputationHalfPoints().
        public String addColorReputationColor;  //"white"/"blue"/"black"/"red"/"green"
        public int addColorReputationAmount = 0;

        // Mod addition (Skip Tutorial, 2026-08-11): runs an arbitrary ConsoleCommandInterpreter
        // command, e.g. "teleport to poi \"Spawn\"" - the same command an item's commandOnUse
        // already routes through (see InventoryScene.java), just reachable from a quest dialog
        // action too. Deliberately generic rather than a single-purpose "teleportToPOI" field -
        // reuses the interpreter's existing, already-proven command set instead of adding a
        // second, narrower mechanism.
        public String runCommand;

        // Edition-restriction stale-bake-in fix (2026-08-13) - set on the "yes"/"repair" action of
        // TownRestoration's town-restore/shop-rebuild dialogs, EconomyBuildings.buildOption(NONE)
        // (plain Card Shop rebuild), and EconomyBuildings.buildSimpleRepairDialog(), so a freshly-
        // restored/rebuilt shop immediately reflects the player's current unlockedEditions instead
        // of whatever AI-color/neutral shard it was born with at MapStage's original (necessarily
        // pre-restoration) build. See MapStage.refreshAllShopRewards(String) for what this
        // actually does - refreshes every shop in the current town, not just one object, so no ID
        // payload is needed here. Null/empty = off; non-null = the trigger label passed through to
        // [TFR-ShopEditions] logging (adversarial review, 2026-08-13 - a hardcoded label here
        // couldn't distinguish which of the 4 call sites actually fired).
        public String refreshShopRewardsTrigger = null;

        // Card Shop Type chooser (mod addition, 2026-08-30): pins an explicitly-chosen shop type
        // onto one shop slot when this option's purchase completes. Payload is
        // "<objectId>:<shopName>" - unlike refreshShopRewardsTrigger above this DOES carry an id,
        // since it targets a single slot rather than every shop in town. Handled in
        // MapDialog.setEffects(); produced by EconomyBuildings.buildCardShopChooser(), ultimately
        // calls MapStage.setShopType(). Null/empty = off.
        public String pinShopType = null;

        // Generic dungeon-clear trigger (mod addition, 2026-08-23): manually fires the same
        // despawn-on-clear treatment DungeonRotation.onDungeonClear() already gives an ordinary
        // combat win (see AdventureQuestController.updateQuestsWin(), which only calls it from a
        // tracked "defeated the last enemy" event). Needed for any dungeon whose "clear" is
        // resolved through dialogue/quest actions instead of combat - e.g. a riddle dungeon whose
        // correct final answer just deletes its own map object - since that path never generates
        // the win event the despawn hook normally rides on. Deliberately generic (not scoped to
        // one dungeon) so any future non-combat "clear" can reuse it. Safe to fire while still
        // standing inside the dungeon: onDungeonClear() only touches the POI's World-level
        // active/rotation bookkeeping, not the currently-loaded MapStage. No-op via
        // onDungeonClear's own gating for non-rotatable POIs, story targets, dungeon-rotation-
        // disabled planes, etc. - same rules as an ordinary combat clear.
        public boolean triggerDungeonClear = false;

        public ActionData(){}

        public ActionData(ActionData other){
            removeItem = other.removeItem;
            addItem = other.addItem;
            addLife = other.addLife;
            addGold = other.addGold;
            addShards = other.addShards;
            addWood = other.addWood;
            addStone = other.addStone;
            deleteMapObject = other.deleteMapObject;
            activateMapObject = other.activateMapObject;
            battleWithActorID = other.battleWithActorID;
            giveBlessing = other.giveBlessing;
            setColorIdentity = other.setColorIdentity;
            advanceQuestFlag = other.advanceQuestFlag;
            advanceMapFlag = other.advanceMapFlag;
            setEffect = other.setEffect;
            setQuestFlag = new QuestFlag();
            if (other.setQuestFlag != null) {
                setQuestFlag.key = other.setQuestFlag.key;
                setQuestFlag.val = other.setQuestFlag.val;
            }
            setMapFlag = new QuestFlag();
            if (other.setMapFlag != null) {
                setMapFlag.key = other.setMapFlag.key;
                setMapFlag.val = other.setMapFlag.val;
            }
            grantRewards = other.grantRewards.clone();
            grantRewardsChoice = other.grantRewardsChoice.clone();
            issueQuest = other.issueQuest;
            addMapReputation = other.addMapReputation;
            POIReference = other.POIReference;
            addColorReputationColor = other.addColorReputationColor;
            addColorReputationAmount = other.addColorReputationAmount;
            runCommand = other.runCommand;
            refreshShopRewardsTrigger = other.refreshShopRewardsTrigger;
            pinShopType = other.pinShopType;
            triggerDungeonClear = other.triggerDungeonClear;
        }
    }

    static public class ConditionData implements Serializable {
        private static final long serialVersionUID = 1L;
        static public class QueryQuestFlag implements Serializable {
            private static final long serialVersionUID = 1L;
            public String key;
            public String op;
            public int val;
        }
        public String item;
        public int actorID = 0;                    //Check for an actor ID.
        public String hasBlessing = null;          //Check for specific blessing, if named.
        public int hasGold = 0;                    //Check for player gold. True if gold is equal or higher than X.
        public int hasShards = 0;                  //Check player's mana shards. True if equal or higher than X.
        public int hasMapReputation = Integer.MIN_VALUE; //Check for player reputation in this POI. True if reputation is equal or higher than X.
        public int hasLife = 0;                    //Check for player life. True if life is equal or higher than X.
        public String colorIdentity = null;        //Check for player's current color identity.
        public String checkCharacterFlag = null;       //Check if a character flag is not 0. False if equals 0 (not started, not set).
        public String checkQuestFlag = null;       //Check if a quest flag is not 0. False if equals 0 (not started, not set).
        public String checkMapFlag = null;         //Check if a map flag is not 0. False if equals 0 (not started, not set).
        public QueryQuestFlag getCharacterFlag = null; //Check for value of a flag { <flagID>, <comparison>, <value> }
        public QueryQuestFlag getQuestFlag = null; //Check for value of a flag { <flagID>, <comparison>, <value> }
        public QueryQuestFlag getMapFlag = null;   //Check for a local dungeon flag ("map flag").
        public boolean not = false;                //Reverse the result of a condition ("actorID":"XX" + "not":true => true if XX is not in the map.)
    }
}
