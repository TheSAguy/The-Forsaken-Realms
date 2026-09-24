package forge.adventure.stage;

import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.graphics.g2d.Sprite;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.utils.Array;
import forge.adventure.data.BiomeSpriteData;
import forge.adventure.pointofintrest.PointOfInterest;
import forge.adventure.pointofintrest.PointOfInterestChanges;
import forge.adventure.scene.MapViewScene;
import forge.adventure.util.Config;
import forge.adventure.world.World;
import forge.adventure.world.WorldSave;
import org.apache.commons.lang3.tuple.Pair;

import java.util.List;

/**
 * Sprite actor that will render trees and rocks on the over world
 */
public class MapSprite extends Actor {

    static public int BackgroundLayer = -1;
    static public int SpriteLayer = 0;
    private Sprite spriteStar = null;
    private Sprite spriteMagnifier = null;
    TextureRegion texture;
    boolean isCaveDungeon, isOldorVisited, isBookmarked;
    public MapSprite(Vector2 pos, TextureRegion sprite, PointOfInterest point) {
        // Lazy-initialize shared indicators exactly once
        if (spriteStar == null) {
            spriteStar = Config.instance().getItemSprite("Star");
        }
        if (spriteMagnifier == null) {
            spriteMagnifier = Config.instance().getItemSprite("Magnifier");
        }

        if (point != null) {
            PointOfInterestChanges changes = WorldSave.getCurrentSave().getPointOfInterestChanges(point.getID());
            setBookmarked(changes.isBookmarked(), point);
            String poiType = point.getData().type;
            isCaveDungeon = "cave".equalsIgnoreCase(poiType) || "dungeon".equalsIgnoreCase(poiType)
                    || "castle".equalsIgnoreCase(poiType) // round 270: castles are explorable too - see below
                    || (poiType != null && poiType.toLowerCase().startsWith("sideboss")); // round 113: side-boss lairs get the unvisited marker too
            // Round 270 (user, with a screenshot of the Wizard Palace next to a marked cave and tower: "This
            // dungeon did not have a magnifying glass, for not yet visited. I know it's a special place, but
            // should still have it"). The marker asks one question - have you been inside? - and that question
            // is just as real for the 13 `castle` POIs, which are hand-built maps you clear like any other.
            // They were left out because the original rule listed the two GENERATED types and round 113 added
            // the side-boss lairs one at a time. Capitals and towns stay out: they are hubs you trade in, not
            // places with an inside to discover.
            if (point.getData().map != null && point.getID() != null) {
                isOldorVisited = changes.hasDeletedObjects();
            }
        } else {
            isBookmarked = false;
            isCaveDungeon = false;
            isOldorVisited = false;
        }
        texture = sprite;
        setPosition(pos.x, pos.y);
        setHeight(texture.getRegionHeight());
        setWidth(texture.getRegionWidth());
    }

    public void checkOut() {
        isOldorVisited = true;
    }

    public void setBookmarked(boolean val, PointOfInterest poi) {
        isBookmarked = val;
        if (poi != null) {
            if (isBookmarked)
                MapViewScene.instance().addBookmark(poi);
            else
                MapViewScene.instance().removeBookmark(poi);
        }
    }

    public static Array<Actor> getMapSprites(int chunkX, int chunkY, int layer) {
        Array<Actor> actorGroup = new Array<>();
        List<Pair<Vector2, Integer>> objects = WorldSave.getCurrentSave().getWorld().GetMapObjects(chunkX, chunkY);
        if (layer == SpriteLayer) {
            List<PointOfInterest> pointsOfInterest = WorldSave.getCurrentSave().getWorld().getPointsOfInterest(chunkX, chunkY);
            for (PointOfInterest poi : pointsOfInterest) {
                Actor sprite = new PointOfInterestMapSprite(poi);
                actorGroup.add(sprite);
            }
        }
        forge.adventure.world.BiomeSprites catalog = WorldSave.getCurrentSave().getWorld().getData().GetBiomeSprites();
        for (Pair<Vector2, Integer> entry : objects) {
            BiomeSpriteData data = WorldSave.getCurrentSave().getWorld().getObject(entry.getValue());
            // Round 303: the layer and the draw size come from the catalog (map_sprites.json) when it lists the doodad.
            // A save keeps each placed doodad's layer from when it was placed, and PlayerBush was placed on layer 1,
            // which is never drawn - the user's bushes never showed.
            BiomeSpriteData current = catalog.getSpriteData(data.name);
            int spriteLayer = current != null ? current.layer : data.layer;
            if (spriteLayer != layer)
                continue;
            Sprite biomeSprite = catalog.getSprite(data.name, (int) entry.getKey().x + (int) entry.getKey().y * 11483);
            if (biomeSprite != null) { //null means invalid and will cause blackscreen, investigate why this would happen...
                MapSprite sprite = new MapSprite(entry.getKey(), biomeSprite, null);
                if (current != null && current.scale > 0f && current.scale != 1f)
                    sprite.setRegionScale(current.scale);
                actorGroup.add(sprite);
            }
        }
        return actorGroup;
    }

    /**
     * Fog of war: is this sprite still under unexplored ground? Checks the sprite's center, not its
     * bottom-left corner: for multi-tile buildings (towns, castles), the corner tile can sit outside
     * the player's actual approach path even while they're standing right at the entrance, leaving
     * the icon permanently hidden.
     * <p>
     * Round 290: a method rather than inline in draw(), so PointOfInterestMapSprite's corner icons -
     * the guard shields and the teleporter - ask the same question. They were drawn after draw() had
     * already returned for the town, so an AI town's guards stood alone in the black fog.
     */
    protected boolean isHiddenByFog() {
        World world = WorldSave.getCurrentSave().getWorld();
        int tileSize = world.getTileSize();
        int centerTileX = (int) ((getX() + getWidth() / 2f) / tileSize);
        int centerTileY = (int) ((getY() + getHeight() / 2f) / tileSize);
        return !world.isExploredWorld(centerTileX, centerTileY);
    }

    // Round 303: a doodad drawn at a fraction of its region (BiomeSpriteData.scale) - an HD doodad is a 32 px picture
    // of one 16-unit tile. Anchored at the bottom-left, like the native draw.
    private float regionScale = 1f;

    public void setRegionScale(float regionScale) {
        this.regionScale = regionScale;
        setWidth(texture.getRegionWidth() * regionScale);
        setHeight(texture.getRegionHeight() * regionScale);
    }

    // Overridable draw-size multiplier, native size when 1f (the default for every non-town
    // MapSprite). PointOfInterestMapSprite overrides this for ruined/player-restored towns.
    protected float getDrawScale() {
        return 1f;
    }

    // Round 329: how far the art is drawn from the actor's position - 0 for every sprite but a town whose swapped-in
    // art is not the size of its footprint (PointOfInterestMapSprite).
    protected float artShiftX() {
        return 0f;
    }

    protected float artShiftY() {
        return 0f;
    }

    //BitmapFont font;
    @Override
    public void draw(Batch batch, float parentAlpha) {
        if (texture == null)
            return;
        if (isHiddenByFog())
            return;
        float x = getX() + artShiftX(), y = getY() + artShiftY(); // round 329
        float scale = getDrawScale();
        if (scale == 1f) {
            if (regionScale == 1f)
                batch.draw(texture, x, y);
            else
                batch.draw(texture, x, y, getWidth(), getHeight());
        } else {
            // Grown symmetrically around the icon's own center (not just from its bottom-left
            // corner) so a scaled-up sprite doesn't visually drift off its actual tile.
            float w = texture.getRegionWidth() * scale;
            float h = texture.getRegionHeight() * scale;
            batch.draw(texture, x - (w - texture.getRegionWidth()) / 2f,
                    y - (h - texture.getRegionHeight()) / 2f, w, h);
        }
        // Round 289: this field is `spriteMagnifier` since upstream's 09.22 rename of `magnifier`.
        if (isCaveDungeon && !isOldorVisited && spriteMagnifier != null) {
            spriteMagnifier.setScale(0.7f, 0.7f);
            spriteMagnifier.setPosition(x - 7, y + 2);
            spriteMagnifier.draw(batch, parentAlpha);
        }
        if (isBookmarked && spriteStar != null) {
            spriteStar.setScale(0.7f, 0.7f);
            spriteStar.setPosition(x + getWidth() - 8, y + getHeight() / 1.5f);
            spriteStar.draw(batch, parentAlpha);
        }
    }

}
