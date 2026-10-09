package forge.adventure.data;

import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.Json;
import forge.adventure.util.Config;
import forge.adventure.util.Paths;

import java.io.Serializable;

/**
 * Data class that will be used to read Json configuration files
 * BiomeData
 * contains the information for the point of interests like towns and dungeons
 */
public class PointOfInterestData implements Serializable {
    // Save compatibility: pinned 2026-09-03 (round 90) at the value derived from the v1.04 class shape so save compatibility no longer depends on the class not changing.
    private static final long serialVersionUID = -986253670258331930L;

    public String name;
    public String type;
    public int count;
    public String spriteAtlas;
    public String sprite;
    public String map;
    public float radiusFactor;
    public float offsetX=0f;
    public float offsetY=0f;
    public boolean active = true;
    public String[] questTags = new String[0];
    /** Round 334: a quest flag that retires this place for good - once the player holds it, the place leaves the map
     *  on the next day and never rotates back (the Sphinx's Sanctum after its riddles are answered). */
    public String retireOnQuestFlag;
    /** Round 513: a rotating place held in the reserve until this day (1-indexed, World.getCurrentDay) - the user, of the
     *  Sphinx's Sanctum and its 4,000 gold: "the Sphinx cave should not appear in the first week" (8 = from Week 1's
     *  first day). 0 = any day. DungeonRotation.isNotYet. */
    public int notBeforeDay;
    /** Round 433: rotating kinds that share one name here share ONE place on the map - their group shows as many as a
     *  single kind of them would (DungeonRotation.typeKey). Deep Caverns' four kinds: one at a time, not one per land. */
    public String rotationGroup;
    /** Round 466: a set piece that leaves the map once every duelist on it is beaten and nothing is left on its floor,
     *  and comes back after a rest - a cleared boss lair's rules (DungeonRotation.isSetPiece). Valor's Reach Arena. */
    public boolean leavesWhenBeaten;
    /** Round 466: cards every opponent in the place starts with in play once it has come back (the arena's Wastes). */
    public String[] returnStartCards;
    /** Round 468: returnStartCards go to each opponent once more per clear, up to this many times (the arena: 3). */
    public int returnStartCardsMax = 1;
    public DialogData.ActionData.QuestFlag[] questFlagsToActivate = new DialogData.ActionData.QuestFlag[0];
    public String displayName;




    private static Array<PointOfInterestData> pointOfInterestList;
    public static Array<PointOfInterestData> getAllPointOfInterest() {
        if (pointOfInterestList == null) {
            Json json = new Json();
            FileHandle handle = Config.instance().getFile(Paths.POINTS_OF_INTEREST);
            if (handle.exists()) {
                pointOfInterestList = json.fromJson(Array.class, PointOfInterestData.class, handle);
            }

        }
        return pointOfInterestList;
    }
    public static PointOfInterestData getPointOfInterest(String name) {
        for(PointOfInterestData data: new Array.ArrayIterator<>(getAllPointOfInterest())){
            if(data.name.equals(name)) return data;
        }
        return null;
    }
    public PointOfInterestData()
    {

    }
    public PointOfInterestData(PointOfInterestData other)
    {
        name=other.name;
        type=other.type;
        count=other.count;
        spriteAtlas=other.spriteAtlas;
        sprite=other.sprite;
        map=other.map;
        radiusFactor=other.radiusFactor;
        offsetX=other.offsetX;
        offsetY=other.offsetY;
        active=other.active;
        questTags = other.questTags.clone();
        displayName= other.displayName;
        questFlagsToActivate = other.questFlagsToActivate;
        retireOnQuestFlag = other.retireOnQuestFlag; // round 433: both mod fields carried
        notBeforeDay = other.notBeforeDay; // round 513
        rotationGroup = other.rotationGroup;
        leavesWhenBeaten = other.leavesWhenBeaten; // round 466
        returnStartCards = other.returnStartCards;
        returnStartCardsMax = other.returnStartCardsMax; // round 468
    }

    public String getDisplayName() {
        if (displayName == null || displayName.isEmpty()) {
            return name!=null?name:"";
        }
        return displayName;
    }
}
