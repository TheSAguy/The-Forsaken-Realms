package forge.adventure.pointofintrest;

import com.badlogic.gdx.graphics.g2d.Sprite;
import com.badlogic.gdx.math.Rectangle;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.utils.Array;
import forge.adventure.data.DialogData;
import forge.adventure.data.PointOfInterestData;
import forge.adventure.util.*;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Random;

/**
 * Point of interest stored in the world
 */
public class PointOfInterest implements Serializable, SaveFileContent {
    // Save compatibility: pinned 2026-09-03 (round 90) at the value derived from the v1.04 class shape so save compatibility no longer depends on the class not changing.
    private static final long serialVersionUID = 3886002045724608218L;


    @Override
    public void load(SaveFileData saveFileData) {
        position.set(saveFileData.readVector2("position"));
        data=PointOfInterestData.getPointOfInterest(saveFileData.readString("name"));
        rectangle.set(saveFileData.readRectangle("rectangle"));
        spriteIndex=saveFileData.readInt("spriteIndex");
        if (saveFileData.containsKey("active")){
            active = saveFileData.readBool("active");
        }
        else
        {
            active = data.active;
        }
        if (saveFileData.containsKey("displayName")){
            displayName = saveFileData.readString("displayName");
        }
        else
        {
            displayName = data==null?"":data.getDisplayName();
        }

        oldMapId="";
        Array<Sprite> textureAtlas = Config.instance().getPOISprites(this.data);
        sprite = textureAtlas.get(spriteIndex%textureAtlas.size);
    }

    @Override
    public SaveFileData save() {
        SaveFileData data=new SaveFileData();
        data.store("name",this.data.name);
        data.store("position",position);
        data.store("rectangle",rectangle);
        data.store("spriteIndex",spriteIndex);
        data.store("active",active);
        data.store("displayName",getDisplayName());
        data.storeObject("questFlagsToActivate", questFlagsToActivate);

        return data;
    }

    PointOfInterestData data;
    final Vector2 position=new Vector2();
    transient Sprite sprite;
    int spriteIndex;
    final Rectangle rectangle=new Rectangle();
    String oldMapId="";
    boolean active = true;
    private String displayName;
    public ArrayList<DialogData.ActionData.QuestFlag> questFlagsToActivate=new ArrayList<>();
    public PointOfInterest() {
    }
    public PointOfInterest(PointOfInterestData d, Vector2 pos, Random rand) {
        Array<Sprite> textureAtlas = Config.instance().getPOISprites(d);
        if (textureAtlas.isEmpty()) {
            System.out.print("sprite " + d.sprite + " not found");
        }
        spriteIndex = rand.nextInt(Integer.SIZE - 1) % textureAtlas.size;
        sprite = textureAtlas.get(spriteIndex);
        data = d;
        active = d.active;
        position.set(pos);
        questFlagsToActivate.addAll(Arrays.asList(data.questFlagsToActivate));

        rectangle.set(position.x, position.y, sprite.getWidth(), sprite.getHeight());
    }
    public PointOfInterest(PointOfInterestData d, PointOfInterest parent) {
        spriteIndex = parent.spriteIndex;
        sprite = parent.sprite;
        data = d;
        active = d.active;
        position.set(parent.position);
        oldMapId=parent.getID();
        rectangle.set(position.x, position.y, sprite.getWidth(), sprite.getHeight());
    }
    public Sprite getSprite() {
        return sprite;
    }

    public Vector2 getPosition() {
        return position;
    }

    public Vector2 getTilePosition(int tileSize) {
        return new Vector2(((position.x + (sprite.getWidth() / 2)) / tileSize), position.y / tileSize);
    }

    public Rectangle getBoundingRectangle() {
        return rectangle;
    }

    public Vector2 getCenter() {
        return rectangle.getCenter(new Vector2());
    }

    public PointOfInterestData getData() {
        return data;
    }

    // Territory Control (MOD_SCOPE.md #7): rebuilds this POI's sprite/rectangle/active-state from
    // a *different* PointOfInterestData, in place - used when a captured neutral town becomes a
    // real instance of the capturing color's own town (e.g. "Waste Town Identity" -> "Forest Town
    // Identity"). Mutates in place rather than replacing the object (the two-arg constructor above
    // does the equivalent for a fresh object) so every existing reference/cache to this POI - the
    // world's own POI registry, its rendered PointOfInterestMapSprite, anything else - stays valid
    // without needing to be found and updated individually. getID() incorporates data.name, so
    // this also naturally gives the transformed POI a fresh PointOfInterestChanges entry - it
    // doesn't inherit the old town's shop-rebuild/Job-Board state, which is intentional: it's a
    // genuinely different town now, not a reskinned wasteland one.
    public void transformInto(PointOfInterestData newData, Random random) {
        transformInto(newData, random, false);
    }

    /**
     * preserveDisplayName=true keeps the town's given name across the transformation (2026-08-08:
     * the unconditional null wipe here was why every gen-time color-town-to-wasteland sweep and
     * every mage capture reverted a uniquely-named town to its template's generic name - "sends a
     * mage toward Waste Town Generic!"). Ownership changes hands; the town keeps its name.
     * Callers that WANT the template name (capital promotion - "Plains Capital" is the identity)
     * pass false / use the 2-arg overload.
     */
    public void transformInto(PointOfInterestData newData, Random random, boolean preserveDisplayName) {
        Array<Sprite> textureAtlas = Config.instance().getPOISprites(newData);
        spriteIndex = random.nextInt(Integer.SIZE - 1) % textureAtlas.size;
        sprite = textureAtlas.get(spriteIndex);
        data = newData;
        active = newData.active;
        if (!preserveDisplayName)
            displayName = null; // falls back to newData.getDisplayName() on next access
        rectangle.set(position.x, position.y, sprite.getWidth(), sprite.getHeight());
    }

    public long getSeedOffset() {
        return  (long)position.x*715567   +(long)position.y+(data.name+"/"+oldMapId).hashCode();
    }

    public String getID() {
        return getSeedOffset()+data.name+"/"+data.map;
    }

    public boolean getActive() {
        // The persisted `active` field was previously write-only (saved/loaded but never consulted
        // - only the quest-flag gates below were). Dungeon rotation (MOD_SCOPE.md, 2026-08-08) now
        // uses it as the despawn/respawn switch: honoring it here makes a hidden POI vanish
        // everywhere getActive() is already checked - the overworld sprite draw
        // (PointOfInterestMapSprite), world entry collision (WorldStage), and new-quest target
        // selection (AdventureQuestStage's validPOIs filter) - with persistence already in place.
        // No data entry ships with active=false (verified across plane + common), so stock
        // behavior is unchanged until something calls setActive(false) at runtime.
        if (!active)
            return false;
        for (DialogData.ActionData.QuestFlag flag : questFlagsToActivate) {
            if (Current.player().getQuestFlag(flag.key) < flag.val){
                return false;
            }
        }
        return true;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public Vector2 getNavigationVector(Vector2 origin){
        Vector2 navVector = new Vector2(rectangle.x + rectangle.getWidth() / 2, rectangle.y + rectangle.getHeight() / 2);
        if (origin != null) navVector.sub(origin);

        return navVector;
    }

    public String getDisplayName() {
        if (displayName == null || displayName.isEmpty())
            displayName = data.getDisplayName();
        return displayName;
    }
    public void setDisplayName(String val) {
        displayName = val;
    }

    public boolean hasDisplayName(){
        return displayName!= null && !displayName.isEmpty();
    }
}
