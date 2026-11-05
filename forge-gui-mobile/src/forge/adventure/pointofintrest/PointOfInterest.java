package forge.adventure.pointofintrest;

import com.badlogic.gdx.graphics.g2d.Sprite;
import com.badlogic.gdx.math.Rectangle;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.utils.Array;
import forge.adventure.data.AdventureQuestData;
import forge.adventure.data.AdventureQuestStage;
import forge.adventure.data.DialogData;
import forge.adventure.data.PointOfInterestData;
import forge.adventure.util.*;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.function.ToIntFunction;

/**
 * Point of interest stored in the world
 */
public class PointOfInterest implements Serializable, SaveFileContent {
    // Save compatibility: pinned 2026-09-03 (round 90) at the value derived from the v1.04 class shape so save compatibility no longer depends on the class not changing.
    private static final long serialVersionUID = 3886002045724608218L;


    /** Round 906 (Fable P3-17): whether this saved place still has a template and a sprite. Without one, load() threw and
     *  the whole save refused to open; PointOfInterestMap leaves such a place out. No shipped place is one today. */
    static boolean loadable(SaveFileData saveFileData) {
        String name = saveFileData.readString("name");
        PointOfInterestData template = PointOfInterestData.getPointOfInterest(name);
        if (template != null && Config.instance().getPOISprites(template).size > 0)
            return true;
        System.out.println("[TFR-POI] " + name + " left out of the world - " + (template == null ? "no such place" : "no sprite")
                + " in this version");
        return false;
    }

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

        // Round 970 (Fable P2-16): the quest-flag gates come back from the template. Stock never read them back (saved
        // below since 2023), so after any load every gated place was open. Not read from the save: a gate a later
        // version drops (round 970 dropped the seven Quest_* gates) is gone from old saves too.
        questFlagsToActivate.clear();
        questFlagsToActivate.addAll(gatesOf(data));
        gateState = 0;
        if (!questFlagsToActivate.isEmpty())
            System.out.println("[TFR-QuestGate] " + getEnglishDisplayName() + ": gate " + describe(questFlagsToActivate)
                    + " restored from the template");

        oldMapId="";
        Array<Sprite> textureAtlas = Config.instance().getPOISprites(this.data);
        spriteIndex = spreadZeroSpriteIndex(spriteIndex, textureAtlas.size, this.data, position);
        sprite = textureAtlas.get(spriteIndex%textureAtlas.size);
        // Round 380 (the new dungeon entrances): the rectangle is the sprite's size at world-gen, restored from the save -
        // a place whose art changed size since (the six 48-px landmark caves were 32) kept the old box, its map label, pin
        // and reveal centre half a tile off. Every constructor sets it from the sprite; so does a load now.
        rectangle.setSize(sprite.getWidth(), sprite.getHeight());
    }

    @Override
    public SaveFileData save() {
        SaveFileData data=new SaveFileData();
        data.store("name",this.data.name);
        data.store("position",position);
        data.store("rectangle",rectangle);
        data.store("spriteIndex",spriteIndex);
        data.store("active",active);
        data.store("displayName",getEnglishDisplayName());
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
    /** Round 970: the gate's last state, for the [TFR-QuestGate] line (0 not yet seen, 1 closed, 2 open, 3 held open). */
    private transient int gateState;
    public PointOfInterest() {
    }
    public PointOfInterest(PointOfInterestData d, Vector2 pos, Random rand) {
        Array<Sprite> textureAtlas = Config.instance().getPOISprites(d);
        if (textureAtlas.isEmpty()) {
            System.out.print("sprite " + d.sprite + " not found");
        }
        spriteIndex = spreadZeroSpriteIndex(rand.nextInt(Integer.SIZE - 1) % textureAtlas.size, textureAtlas.size, d, pos);
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
    // Round 122 (cave icon variants, MOD_SCOPE #11): every POI ever saved rolled its spriteIndex
    // against a one-sprite list (nextInt % 1 == 0), so a sprite set that has since grown - the 81
    // plain "Cave" entries now draw from per-biome sets of 12-25 regions in caves.atlas - would
    // show only its FIRST variant on every cave of an existing world. A zero index is therefore
    // re-derived from the POI's own name and position: deterministic (the same POI resolves to
    // the same variant in the constructor and on every load, so nothing changes between
    // sessions), 0 stays reachable, and the result persists on the next save like any other
    // roll. One-sprite sets are untouched.
    private static int spreadZeroSpriteIndex(int index, int size, PointOfInterestData d, Vector2 pos) {
        if (index != 0 || size <= 1 || d == null || d.name == null || pos == null)
            return index;
        int hash = (d.name + "@" + (int) pos.x + "," + (int) pos.y).hashCode();
        return Math.floorMod(hash, size);
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
        if (questFlagsToActivate.isEmpty())
            return true;
        String closed = closedBy(questFlagsToActivate, Current.player()::getQuestFlag);
        String quest = closed == null ? null : questTargeting();
        // Round 970: a place an active quest stage is sending the player to stays open - a save made after a load,
        // when every gate was open, can hold such a quest ("(Almost) Open for Business" bound to Emrakul's Castle).
        int state = closed == null ? 2 : quest == null ? 1 : 3;
        if (state != gateState) {
            gateState = state;
            System.out.println("[TFR-QuestGate] " + getEnglishDisplayName() + (state == 2 ? ": open (" + describe(questFlagsToActivate) + " reached)"
                    : state == 1 ? ": hidden (" + closed + ")" : ": held open (" + closed + ") - the quest \"" + quest + "\" sends the player there"));
        }
        return state != 1;
    }

    /** Round 970: the template's gates, as the constructor takes them. */
    static List<DialogData.ActionData.QuestFlag> gatesOf(PointOfInterestData d) {
        if (d == null || d.questFlagsToActivate == null)
            return new ArrayList<>();
        return new ArrayList<>(Arrays.asList(d.questFlagsToActivate));
    }

    /** Round 970: the first gate not reached, as "mainQuest 1 < 2", or null when every gate is open. */
    static String closedBy(List<DialogData.ActionData.QuestFlag> gates, ToIntFunction<String> questFlag) {
        for (DialogData.ActionData.QuestFlag flag : gates) {
            int now = questFlag.applyAsInt(flag.key);
            if (now < flag.val)
                return flag.key + " " + now + " < " + flag.val;
        }
        return null;
    }

    static String describe(List<DialogData.ActionData.QuestFlag> gates) {
        StringBuilder sb = new StringBuilder();
        for (DialogData.ActionData.QuestFlag flag : gates)
            sb.append(sb.length() == 0 ? "" : ", ").append(flag.key).append(" >= ").append(flag.val);
        return sb.toString();
    }

    /** Round 970: the name of an active quest whose active stage targets this place, or null. */
    private String questTargeting() {
        for (AdventureQuestData quest : Current.player().getQuests()) {
            for (AdventureQuestStage stage : quest.stages) {
                if (stage == null || stage.getStatus() != AdventureQuestController.QuestStatus.ACTIVE)
                    continue;
                PointOfInterest target = stage.getTargetPOI();
                if (target == this || target != null && target.data != null && target.data.name.equals(data.name)
                        && target.position.equals(position))
                    return quest.getName();
            }
        }
        return null;
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
        String englishName = getEnglishDisplayName();
        return forge.Forge.getLocalizer().getMessageorUseDefault(
            "adv.poi." + englishName.replaceAll("[^A-Za-z0-9]", "") + ".displayName", englishName);
    }
    private String getEnglishDisplayName() {
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
