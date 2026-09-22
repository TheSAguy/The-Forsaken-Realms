package forge.adventure.stage;

import com.badlogic.gdx.graphics.Color;
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
    /** Round 254: the largest entry box any POI gets, in pixels - two 16px tiles. See the constructor. */
    private static final float ENTRY_BOX_MAX = 32f;
    MapSprite mapSprite;
    // Ruin/Player-town main-map icon bump (2026-08-25 user request: "still a little small
    // compared to the Neutral town or AI towns... increase the size of Ruin Towns and Player
    // towns by 15%"), set fresh in draw() alongside the texture choice itself.
    private boolean drawEnlarged;

    public PointOfInterestMapSprite(PointOfInterest point) {
        super(point.getPosition(), point.getSprite(), point);
        pointOfInterest = point;
        mapSprite = this;
        // Round 254 (user, on Orazca: "the radius to enter the center ruin seems huge"). This box is what
        // WorldStage tests the player against to enter a POI, and it used to be the whole texture - which for a
        // Center Town / Orazca is CenterTownNeutral at 64x64, four tiles, while the broken-town art actually
        // drawn over a ruin is a fraction of that. The player was pulled in from four tiles out. Cap it at two
        // tiles, CENTRED on the sprite in both axes, so entering means walking onto the icon itself rather than
        // into its aura, and it reads the same from every direction. (First cut sat the box on the sprite's base,
        // which made the north side of a 64x64 town unenterable at the tiles the player naturally walks - caught
        // in the agent game.) A POI whose art is already this small (most caves and dungeons, the old campfire)
        // is untouched.
        float entryW = Math.min(texture.getRegionWidth(), ENTRY_BOX_MAX);
        float entryH = Math.min(texture.getRegionHeight(), ENTRY_BOX_MAX);
        boundingRect = new Rectangle(getX() + (texture.getRegionWidth() - entryW) / 2f,
                getY() + (texture.getRegionHeight() - entryH) / 2f, entryW, entryH);
    }

    public PointOfInterest getPointOfInterest() {
        return pointOfInterest;
    }

    public MapSprite getMapSprite() {
        return this;
    }

    public Rectangle getBoundingRect() {
        return boundingRect;
    }

    @Override
    protected float getDrawScale() {
        return drawEnlarged ? 1.15f : 1f;
    }

    // Round 232 (user, on round 231's preview: "The Capitol image looks good, the town seems like the icons
    // are a little high, needs to come down a little to start same level as town image starts"). The box the
    // texture is actually DRAWN in. MapSprite.draw() grows a scaled sprite around its own center, so a 48x48
    // town at 1.15x starts 3.6 px left of getX() and 3.6 px below getY() - and both corner icons, placed at
    // getX()/getY(), floated that far above the town's base. The Capitol is drawn at 1x, which is why it
    // already looked right. These mirror draw()'s own arithmetic, so the icons follow whatever it does.
    private float drawnGrowth(int nativeSize) {
        return (nativeSize * getDrawScale() - nativeSize) / 2f;
    }

    private float drawnLeft() {
        return getX() - (texture == null ? 0f : drawnGrowth(texture.getRegionWidth()));
    }

    private float drawnRight() {
        return texture == null ? getX() : getX() + texture.getRegionWidth() + drawnGrowth(texture.getRegionWidth());
    }

    private float drawnBottom() {
        return getY() - (texture == null ? 0f : drawnGrowth(texture.getRegionHeight()));
    }

    @Override
    public void draw(Batch batch, float parentAlpha) {
        // Round 289 keeps upstream's 09.22 null guard on top of this block.
        if (pointOfInterest != null && pointOfInterest.getActive()) {
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
            drawTeleporterIndicator(batch, parentAlpha); // round 231
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
                batch.draw(icon, drawnLeft() + xOffset, drawnBottom(), GUARD_ICON_DRAW_SIZE, GUARD_ICON_DRAW_SIZE); // round 232
                xOffset += GUARD_ICON_DRAW_SIZE;
            }
        } else {
            for (int i = 0; i < changes.getGuardCount(); i++) {
                TextureRegion icon = EconomyBuildings.getGuardTierIconSprite(changes.getGuardTier(i));
                if (icon == null)
                    continue;
                batch.draw(icon, drawnLeft() + xOffset, drawnBottom(), GUARD_ICON_DRAW_SIZE, GUARD_ICON_DRAW_SIZE); // round 232
                xOffset += GUARD_ICON_DRAW_SIZE;
            }
        }
        batch.setColor(pr, pg, pb, pa);
    }

    // Round 231 (user, with a mock-up: "For towns/Capitol, that has a Teleporter. Can we add a little icon
    // on the overworld map, kinda like the guards. But let's have it to the right vs. Guards on left").
    // The mirror of drawGuardIndicator(): the bottom-RIGHT corner of the sprite, measured from the texture
    // actually being drawn (a restored town swaps in its own 48x48 art, so the actor's size can be stale).
    // Round 232: both icons sit on the DRAWN box - drawnLeft()/drawnRight()/drawnBottom() - so on a 1.15x
    // town they start level with the town's base and flush with its sides, exactly as on the 1x Capitol.
    // Worst case the Capitol shows two guards (24) and the portal (16) on a 64-wide sprite (a town: 12 + 16
    // on 55), so they never touch.
    // Drawn at the portal frame's native 16x16 - the guard art is 8x8 scaled UP to 12, which stays crisp
    // under Nearest filtering; scaling 16 DOWN to 12 would drop pixel rows instead.
    // Same ownership rule as the mini-map's Names-view glyph (round 223): a restored town or the Capitol,
    // with a Teleporter built. A captured town loses its buildings, so the icon goes with it.
    private static final float TELEPORTER_ICON_DRAW_SIZE = 16f;
    // Round 234: keyed by POI id and static, NOT a field of this sprite. Map sprites are rebuilt every time
    // their chunk reloads, so the round-231 per-sprite flag printed the same town again and again as the
    // player walked about - 10 lines for 3 towns in the first session that had it.
    private static final java.util.Set<String> TELEPORTER_ICON_LOGGED_FOR = new java.util.HashSet<>();

    private void drawTeleporterIndicator(Batch batch, float parentAlpha) {
        PointOfInterestChanges changes = WorldSave.getCurrentSave().peekPointOfInterestChanges(pointOfInterest.getID());
        if (changes == null || !changes.hasEconomyBuildingOfType(EconomyBuildings.TELEPORTER))
            return;
        if (pointOfInterest.getData() == null
                || (!TownRestoration.isTownRestored(changes)
                    && !TownRestoration.CAPITOL_POI_NAME.equals(pointOfInterest.getData().name)))
            return;
        TextureRegion icon = EconomyBuildings.getTeleporterMapIcon();
        if (icon == null || texture == null)
            return;
        if (TELEPORTER_ICON_LOGGED_FOR.add(pointOfInterest.getID())) {
            // Once per town per session, not per frame: enough to confirm from forge.log which towns carry the icon.
            System.out.println("[TFR-MapIcon] " + pointOfInterest.getDisplayName() + ": teleporter icon at the sprite's"
                    + " bottom-right (network active=" + EconomyBuildings.isTeleporterNetworkActive() + ")");
        }
        // Snapshot the batch color's components before changing it - see drawGuardIndicator().
        Color prevRef = batch.getColor();
        float pr = prevRef.r, pg = prevRef.g, pb = prevRef.b, pa = prevRef.a;
        batch.setColor(pr, pg, pb, parentAlpha);
        batch.draw(icon, drawnRight() - TELEPORTER_ICON_DRAW_SIZE, drawnBottom(),
                TELEPORTER_ICON_DRAW_SIZE, TELEPORTER_ICON_DRAW_SIZE);
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
