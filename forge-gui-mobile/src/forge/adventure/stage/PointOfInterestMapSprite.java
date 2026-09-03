package forge.adventure.stage;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.math.Rectangle;
import forge.adventure.pointofintrest.PointOfInterest;
import forge.adventure.pointofintrest.PointOfInterestChanges;
import forge.adventure.util.EconomyBuildings;
import forge.adventure.util.TownRestoration;
import forge.adventure.world.WorldSave;

/**
 * MapSprite for points of interest to add a bounding rect for collision detection
 */
public class PointOfInterestMapSprite extends MapSprite {
    PointOfInterest pointOfInterest;
    Texture debugTexture;
    Rectangle boundingRect;
    MapSprite mapSprite;
    // Ruin/Player-town main-map icon bump (2026-08-25 user request: "still a little small
    // compared to the Neutral town or AI towns... increase the size of Ruin Towns and Player
    // towns by 15%"), set fresh in draw() alongside the texture choice itself.
    private boolean drawEnlarged;

    public PointOfInterestMapSprite(PointOfInterest point) {
        super(point.getPosition(), point.getSprite(), point);
        pointOfInterest = point;
        mapSprite = this;
        boundingRect = new Rectangle(getX(), getY(), texture.getRegionWidth(), texture.getRegionHeight());
    }

    public PointOfInterest getPointOfInterest() {
        return pointOfInterest;
    }

    public MapSprite getMapSprite() {
        return mapSprite;
    }

    private Texture getDebugTexture() {
        if (debugTexture == null) {
            Pixmap pixmap = new Pixmap(texture.getRegionWidth(), texture.getRegionHeight(), Pixmap.Format.RGBA8888);
            pixmap.setColor(Color.RED);
            pixmap.drawRectangle(0, 0, (int) getWidth(), (int) getHeight());
            debugTexture = new Texture(pixmap);
            pixmap.dispose();
        }
        return debugTexture;
    }

    public Rectangle getBoundingRect() {
        return boundingRect;
    }

    @Override
    protected float getDrawScale() {
        return drawEnlarged ? 1.15f : 1f;
    }

    @Override
    public void draw(Batch batch, float parentAlpha) {
        if (pointOfInterest.getActive()) {
            // Read the POI's own current sprite fresh rather than caching it, since Territory
            // Control (MOD_SCOPE.md #7) can change it after this actor was constructed
            // (PointOfInterest.transformInto() when a captured town becomes a different POI).
            TextureRegion brokenTexture = TownRestoration.getBrokenTownSprite(pointOfInterest);
            if (brokenTexture != null) {
                texture = brokenTexture;
                drawEnlarged = true;
            } else {
                // Player-restored wasteland town (2026-08-25) - dedicated art distinct from the
                // shared "WasteTown" look every functioning-neutral town still uses.
                TextureRegion playerTownTexture = TownRestoration.getPlayerTownSprite(pointOfInterest);
                texture = playerTownTexture != null ? playerTownTexture : pointOfInterest.getSprite();
                drawEnlarged = playerTownTexture != null;
            }
            super.draw(batch, parentAlpha);
            drawGuardIndicator(batch, parentAlpha);
        }
        //batch.draw(getDebugTexture(),getX(),getY());
    }

    // Guard map indicator (2026-08-11, MOD_SCOPE.md #22) - a small icon in the sprite's bottom-left
    // corner per currently-hired guard (towns cap at 1 guard/1 icon; the Capitol allows 2, and
    // originally only ever drew the single strongest one even with 2 hired - user report
    // 2026-08-11: "only 1 icon appeared... capitol can have two guards, so will need up to two
    // icons"). Icons are laid out left-to-right in hire order, weakest-tier-first is irrelevant
    // here (order doesn't matter, just that both show). A peek (not get) lookup - this runs every
    // frame this POI is on-screen, and must never lazily create a PointOfInterestChanges entry
    // for every town the player merely scrolls past.
    // Drawn size (2026-08-11 user request: "a little small... let's try 12x12") - the source art
    // is still the native 8x8 crop (`guard_icons.atlas`'s own region size), just scaled up at
    // draw time via explicit width/height rather than icon.getRegionWidth()/getRegionHeight() -
    // the atlas is Nearest-filtered like every other asset in this project, so the 1.5x upscale
    // stays crisp/pixel-art-consistent, no new art needed.
    private static final float GUARD_ICON_DRAW_SIZE = 12f;

    private void drawGuardIndicator(Batch batch, float parentAlpha) {
        PointOfInterestChanges changes = WorldSave.getCurrentSave().peekPointOfInterestChanges(pointOfInterest.getID());
        // AI guard dots (MOD_SCOPE #87, user spec 2026-09-03): AI color towns show ONE dot whose
        // tier is their guard level (Apprentice..Archmage); AI capitals always show TWO Archmage
        // dots. Neutral/ruined towns and the player's own towns never get these (the player's
        // towns show their hired guards below instead).
        String[] aiDots = aiGuardDots(changes);
        boolean hasHired = changes != null && changes.getGuardCount() > 0;
        if (aiDots == null && !hasHired)
            return;
        // batch.getColor() returns the batch's *internal* Color by reference, not a copy -
        // snapshot the primitive components before calling setColor and restore from those
        // (same fix as the 2026-08-10 "twinkle flicker" bug - restoring from the live reference
        // after mutating it would just re-apply the already-changed value to itself).
        Color prevRef = batch.getColor();
        float pr = prevRef.r, pg = prevRef.g, pb = prevRef.b, pa = prevRef.a;
        batch.setColor(pr, pg, pb, parentAlpha);
        float xOffset = 0f;
        if (aiDots != null) {
            for (String tier : aiDots) {
                TextureRegion icon = EconomyBuildings.getGuardTierIconSprite(tier);
                if (icon == null)
                    continue;
                batch.draw(icon, getX() + xOffset, getY(), GUARD_ICON_DRAW_SIZE, GUARD_ICON_DRAW_SIZE);
                xOffset += GUARD_ICON_DRAW_SIZE;
            }
        } else {
            for (int i = 0; i < changes.getGuardCount(); i++) {
                TextureRegion icon = EconomyBuildings.getGuardTierIconSprite(changes.getGuardTier(i));
                if (icon == null)
                    continue;
                batch.draw(icon, getX() + xOffset, getY(), GUARD_ICON_DRAW_SIZE, GUARD_ICON_DRAW_SIZE);
                xOffset += GUARD_ICON_DRAW_SIZE;
            }
        }
        batch.setColor(pr, pg, pb, pa);
    }

    /** Tier names of the AI guard dots for this POI, or null when it shows none. */
    private String[] aiGuardDots(PointOfInterestChanges changes) {
        forge.adventure.data.PointOfInterestData data = pointOfInterest.getData();
        if (data == null || forge.adventure.util.ColorReputation.colorOfTown(data) == null)
            return null; // not an AI color town/capital (Waste/ruined/neutral, Spawn, player Capitol)
        if (TownRestoration.isTownRestored(changes))
            return null; // captured by the player - now theirs
        if ("capital".equals(data.type))
            return new String[]{"Mythic", "Mythic"};
        int level = changes == null ? 0 : changes.getAiGuardLevel();
        if (level <= 0)
            return null;
        String[] tiers = EconomyBuildings.GUARD_TIERS_ASCENDING;
        return new String[]{tiers[Math.min(level, tiers.length) - 1]};
    }
}
