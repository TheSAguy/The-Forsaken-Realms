package forge.adventure.character;

import com.badlogic.gdx.graphics.g2d.Batch;
import forge.adventure.stage.MapStage;
import forge.adventure.util.MapDialog;
import forge.adventure.util.RubbleOverlay;
import forge.adventure.util.TownRestoration;

/**
 * Designed to add anonymous class for a single action on collision. Optionally gated by town
 * restoration (MOD_SCOPE.md #2): pass a real Tiled object id and the owning MapStage to have this
 * building show as rubble and require a rebuild in a destroyed wasteland town, same as ShopActor -
 * without gating (the original single-arg constructor), it always just runs onCollide, unaffected
 * by town restoration, same as before this existed.
 */
public class OnCollide extends MapActor {

    Runnable onCollide;
    private final MapStage gatedStage;
    // Icon drawn once this gated building is REBUILT, in maps with no baked-in building art
    // (the wasteland town/capital templates) - without it a rebuilt Arena/Spellsmith was simply
    // invisible (user-reported 2026-08-09). Null = draw nothing (all ungated uses, and gated
    // buildings whose map has real baked art).
    // A SUPPLIER, not a plain TextureRegion (2026-08-15 bug fix) - the old plain-TextureRegion
    // version was evaluated exactly once, at map-load time, then cached forever; that's correct
    // for Spellsmith (its icon never changes) but silently froze the Arena's icon at whatever
    // building level it was at load time, since Level 2's own icon exists and the sprite-picking
    // logic is correct but never gets re-evaluated after an in-place level-up (user report:
    // "Arena Level 2 art icon did not update... still showing level 1"). A supplier re-queries
    // live state (e.g. current building level) on every draw() instead, mirroring how
    // ShopActor.draw() already re-reads the Armory's level fresh every frame. Spellsmith's own
    // call site just wraps its unchanging icon in a constant lambda - no behavior change there.
    private java.util.function.Supplier<com.badlogic.gdx.graphics.g2d.TextureRegion> rebuiltIcon;

    public OnCollide(Runnable func) {
        super(0);
        onCollide = func;
        gatedStage = null;
    }

    public OnCollide(Runnable func, int id, MapStage stage) {
        super(id);
        onCollide = func;
        gatedStage = stage;
    }

    public OnCollide withRebuiltIcon(java.util.function.Supplier<com.badlogic.gdx.graphics.g2d.TextureRegion> iconSupplier) {
        rebuiltIcon = iconSupplier;
        return this;
    }

    // Custom rebuild cost/verb (2026-08-12 cost table: the Arena's rebuild price differs from a
    // plain shop's). Null verb = the default plain-shop cost path.
    private int[] rebuildCost;
    private String rebuildVerb;

    public OnCollide withRebuildCost(int gold, int wood, int stone, int shards, String verb) {
        rebuildCost = new int[]{gold, wood, stone, shards};
        rebuildVerb = verb;
        return this;
    }

    private boolean isDestroyed() {
        return gatedStage != null && TownRestoration.isWastelandTown() && !TownRestoration.isShopRebuilt(gatedStage, objectId);
    }

    @Override
    protected void onPlayerCollide() {
        if (isDestroyed()) {
            gatedStage.getPlayerSprite().stop();
            MapDialog dialog;
            if (!TownRestoration.isTownRestored(gatedStage))
                dialog = TownRestoration.buildShopLockedDialog(gatedStage, objectId);
            else if (!TownRestoration.hasReputationForAnotherBuilding(gatedStage.getChanges()))
                dialog = TownRestoration.buildReputationLockedDialog(gatedStage, objectId);
            else if (rebuildVerb != null)
                dialog = TownRestoration.buildRebuildShopDialog(gatedStage, objectId,
                        rebuildCost[0], rebuildCost[1], rebuildCost[2], rebuildCost[3], rebuildVerb);
            else
                dialog = TownRestoration.buildRebuildShopDialog(gatedStage, objectId);
            if (dialog.activate())
                gatedStage.showDialog();
            return;
        }
        try {
            onCollide.run();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public void draw(Batch batch, float alpha) {
        super.draw(batch, alpha);
        if (!isDestroyed()) {
            // Rebuilt gated building in a template with no baked art: draw its icon (user report
            // 2026-08-09 - a restored Arena/Spellsmith showed nothing at all). Same
            // over-footprint placement as ShopActor's icons.
            // isWastelandTownTemplate(), not isWastelandTown() (2026-08-24 fix, same class of bug
            // as ShopActor's identical gate) - "does this map lack baked art", not "is this town
            // ruined". Not currently reachable from player_town.tmx (no Arena/Spellsmith objects
            // there today), but fixed for consistency/future-proofing.
            if (rebuiltIcon != null && gatedStage != null && TownRestoration.isWastelandTownTemplate()) {
                com.badlogic.gdx.graphics.g2d.TextureRegion icon = rebuiltIcon.get();
                if (icon != null)
                    drawOverFootprint(batch, icon);
            }
            return;
        }
        // In the Capitol, a destroyed gated building (Arena, Spellsmith) shows the real
        // broken-shop art instead of the translucent rubble overlay (user spec 2026-08-09,
        // "use the broken shop art for now") - same 32x32-over-footprint placement as
        // ShopActor.drawOverFootprint(). Regular towns keep the rubble overlay.
        if (TownRestoration.isCurrentTownCapitol()) {
            com.badlogic.gdx.graphics.g2d.TextureRegion broken = TownRestoration.getBrokenShopSprite(objectId);
            if (broken != null) {
                drawOverFootprint(batch, broken);
                return;
            }
        }
        RubbleOverlay.draw(batch, getX(), getY(), getWidth(), getHeight(), alpha);
    }

    private void drawOverFootprint(Batch batch, com.badlogic.gdx.graphics.g2d.TextureRegion region) {
        float w = region.getRegionWidth();
        float h = region.getRegionHeight();
        batch.draw(region, getX() + (getWidth() - w) / 2f, getY() + getHeight() - 16f, w, h);
    }
}
