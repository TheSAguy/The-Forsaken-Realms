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
    TextureRegion texture;
    Sprite bookmark = Config.instance().getItemSprite("Star");
    Sprite magnifier = Config.instance().getItemSprite("Magnifier");
    boolean isCaveDungeon, isOldorVisited, isBookmarked;
    public MapSprite(Vector2 pos, TextureRegion sprite, PointOfInterest point) {
        if (point != null) {
            PointOfInterestChanges changes = WorldSave.getCurrentSave().getPointOfInterestChanges(point.getID());
            setBookmarked(changes.isBookmarked(), point);
            String poiType = point.getData().type;
            isCaveDungeon = "cave".equalsIgnoreCase(poiType) || "dungeon".equalsIgnoreCase(poiType)
                    || (poiType != null && poiType.toLowerCase().startsWith("sideboss")); // round 113: side-boss lairs get the unvisited marker too
            if (point.getData().map != null && point.getID() != null) {
                isOldorVisited = changes.hasDeletedObjects();
            }
        } else {
            setBookmarked(false, null);
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
        if (isBookmarked)
            MapViewScene.instance().addBookmark(poi);
        else
            MapViewScene.instance().removeBookmark(poi);

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
        for (Pair<Vector2, Integer> entry : objects) {
            BiomeSpriteData data = WorldSave.getCurrentSave().getWorld().getObject(entry.getValue());
            if (data.layer != layer)
                continue;
            Sprite biomeSprite = WorldSave.getCurrentSave().getWorld().getData().GetBiomeSprites().getSprite(data.name, (int) entry.getKey().x + (int) entry.getKey().y * 11483);
            if (biomeSprite != null) { //null means invalid and will cause blackscreen, investigate why this would happen...
                Actor sprite = new MapSprite(entry.getKey(), biomeSprite, null);
                actorGroup.add(sprite);
            }
        }
        return actorGroup;
    }

    // Overridable draw-size multiplier, native size when 1f (the default for every non-town
    // MapSprite). PointOfInterestMapSprite overrides this for ruined/player-restored towns.
    protected float getDrawScale() {
        return 1f;
    }

    //BitmapFont font;
    @Override
    public void draw(Batch batch, float parentAlpha) {
        if (texture == null)
            return;
        World world = WorldSave.getCurrentSave().getWorld();
        int tileSize = world.getTileSize();
        // Check the sprite's center, not its bottom-left corner: for multi-tile buildings (towns,
        // castles), the corner tile can sit outside the player's actual approach path even while
        // they're standing right at the entrance, leaving the icon permanently hidden.
        int centerTileX = (int) ((getX() + getWidth() / 2f) / tileSize);
        int centerTileY = (int) ((getY() + getHeight() / 2f) / tileSize);
        if (!world.isExploredWorld(centerTileX, centerTileY))
            return;
        float scale = getDrawScale();
        if (scale == 1f) {
            batch.draw(texture, getX(), getY());
        } else {
            // Grown symmetrically around the icon's own center (not just from its bottom-left
            // corner) so a scaled-up sprite doesn't visually drift off its actual tile.
            float w = texture.getRegionWidth() * scale;
            float h = texture.getRegionHeight() * scale;
            batch.draw(texture, getX() - (w - texture.getRegionWidth()) / 2f,
                    getY() - (h - texture.getRegionHeight()) / 2f, w, h);
        }
        if (isCaveDungeon && !isOldorVisited && magnifier != null) {
            magnifier.setScale(0.7f, 0.7f);
            magnifier.setPosition(getX() - 7, getY() + 2);
            magnifier.draw(batch, parentAlpha);
        }
        if (isBookmarked && bookmark != null) {
            bookmark.setScale(0.7f, 0.7f);
            bookmark.setPosition(getRight() - 8, getY() + getHeight() / 1.5f);
            bookmark.draw(batch, parentAlpha);
        }
        //font.draw(batch,String.valueOf(getZIndex()),getX()-(getWidth()/2),getY());
    }

}
