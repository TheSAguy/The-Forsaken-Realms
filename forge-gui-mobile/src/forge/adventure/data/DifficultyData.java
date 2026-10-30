package forge.adventure.data;

import com.badlogic.gdx.utils.ObjectMap;

/**
 * Data class that will be used to read Json configuration files
 * BiomeData
 * contains the information for the difficulties
 */
public class DifficultyData {
    public String name="";
    public int startingLife=10;
    public int startingShards=1;
    public int startingMoney=10;
    // Starting Wood/Stone (2026-08-15 user spec: "give Easy 100 Wood and Stone to start with,
    // and Normal 50 Wood and Stone") - 0 default for any difficulty tier that doesn't set these,
    // same "unset means the base default" convention every other DifficultyData field already
    // follows.
    public int startingWood=0;
    public int startingStone=0;
    // TFR round 872: how many of the race's sets a new game starts with unlocked (Easy 4 ... Insane 1 in TFR's
    // config.json; the "Starting sets" custom difficulty knob). 0 = unset: the old by-index table (4/3/2/1).
    public int startingSets=0;
    public float enemyLifeFactor=1;
    public boolean startingDifficulty;
    public int spawnRank = 1; //0 for "easy", 1 for "normal", 2 for "hard". To filter map spawns based on this.
    public float sellFactor=0.2f;
    public float goldLoss=0.2f;
    public float lifeLoss=0.2f;
    public float shardSellRatio = 0.8f;

    public float rewardMaxFactor=1f;
    public String[] startItems=new String[0];
    public ObjectMap<String,String> starterDecks = null;
    public ObjectMap<String,String> constructedStarterDecks= null;
    public ObjectMap<String,String> pileDecks= null;
    public ObjectMap<String,String> commanderDecks = null;

}
