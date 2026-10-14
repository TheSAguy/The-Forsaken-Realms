package forge.adventure.data;

import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.g2d.Sprite;
import com.badlogic.gdx.graphics.g2d.TextureAtlas;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.Json;
import com.badlogic.gdx.utils.ObjectMap;
import forge.Forge;
import forge.adventure.util.Config;
import forge.adventure.util.Paths;

/**
 * Data class that will be used to read Json configuration files
 * BiomeData
 * contains the a list of all heroes
 */
public class HeroListData {
    static private HeroListData instance;
    private HeroData[] heroes;
    private String avatar;
    private TextureAtlas avatarAtlas;
    private final ObjectMap<String, Array<Sprite>> avatarSprites = new ObjectMap<>();

    static public HeroListData instance() {
        if (instance == null) {
            Json json = new Json();
            FileHandle handle = Config.instance().getFile(Paths.HEROES);
            if (handle.exists()) {
                instance = json.fromJson(HeroListData.class, handle);
                instance.avatarAtlas = Config.instance().getAtlas(instance.avatar);
                // leaving here as reference since PixelArt images use Nearest without MipMaps. By default it shouldn't have any filters
                /*instance.avatarSprites.getTextures().first().setFilter(Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest);*/
            } else {
                throw new RuntimeException("Path not found: " + handle.path());
            }
        }
        return instance;
    }

    public String getHero(int raceIndex, boolean female) {
        HeroData data =  instance().heroes[raceIndex];

        if (female)
            return data.female;
        return data.male;

    }

    /** Round 591: the draw scale of a race's hero sheets (heroes.json "scale"; 1 when absent or not positive). */
    public float getScale(int raceIndex) {
        HeroData data = instance().heroes[raceIndex];
        return data.scale > 0f ? data.scale : 1f;
    }

    // Round 542 (round 538's card-art portraits, the user: "replace the default player portraits with in game card
    // pictures"): a race's portraits come from its own atlas when the plane ships one - PORTRAIT_DIR + the race name in
    // lower case, spaces as underscores (sprites/heroes/portraits/red_dragon.atlas), built by
    // dev-tools/portraits/make_portraits.py - with the same region names as the pixel avatar atlas. One atlas a race, loaded
    // the first time that race's portrait is asked for: in play only the player's own race sits in memory, where one
    // shared page of every race would grow past 100 MB of texture. Without one, the pixel portraits as before.
    private static final String PORTRAIT_DIR = "sprites/heroes/portraits/";
    private final ObjectMap<String, TextureAtlas> portraitAtlases = new ObjectMap<>();

    private TextureAtlas portraitAtlas(HeroData data) {
        if (data.name == null)
            return null;
        if (!portraitAtlases.containsKey(data.name)) {
            String path = PORTRAIT_DIR + data.name.toLowerCase().replace(' ', '_') + ".atlas";
            TextureAtlas atlas = null;
            try {
                FileHandle handle = Config.instance().getFile(path); // null when the plane has no such file
                if (handle != null && handle.exists())
                    atlas = Config.instance().getAtlas(path);
            } catch (Exception e) {
                System.err.println("[TFR-Portraits] " + path + " could not load (" + e + ") - the pixel portraits stand");
            }
            portraitAtlases.put(data.name, atlas);
            System.out.println("[TFR-Portraits] " + data.name + ": " + (atlas != null ? "card portraits from " + path
                    : "no " + path + " - the pixel portraits"));
        }
        return portraitAtlases.get(data.name);
    }

    public TextureRegion getAvatar(int heroRace, boolean isFemale, int avatarIndex) {
        HeroData data = instance().heroes[heroRace];
        String region = isFemale ? data.femaleAvatar : data.maleAvatar;
        TextureAtlas portraits = instance().portraitAtlas(data);
        String key = (portraits != null ? "portrait:" : "") + region;
        Array<Sprite> sprites = instance().avatarSprites.get(key);
        if (sprites == null) {
            sprites = portraits != null ? portraits.createSprites(region) : new Array<>();
            if (sprites.isEmpty()) // no portrait atlas, or none of this gender in it
                sprites = instance().avatarAtlas.createSprites(region);
            instance().avatarSprites.put(key, sprites);
        }
        avatarIndex %= sprites.size;
        if (avatarIndex < 0) {
            avatarIndex += sprites.size;
        }
        return sprites.get(avatarIndex);
    }

    /** The RAW race name from heroes.json (e.g. "Human", "Red Dragon") - config structures like
     *  ConfigData.raceEditions key on this, NOT on getRaces()' localized display labels. */
    public static String getRawRaceName(int raceIndex) {
        HeroData[] all = instance().heroes;
        if (all == null || raceIndex < 0 || raceIndex >= all.length || all[raceIndex] == null)
            return null;
        return all[raceIndex].name;
    }

    public Array<String> getRaces() {
        Array<String> ret = new Array<>();
        for (HeroData hero : instance().heroes) {
            ret.add(Forge.getLocalizer().getMessageorUseDefault("lbl"+hero.name, hero.name));
        }
        return ret;
    }
}
