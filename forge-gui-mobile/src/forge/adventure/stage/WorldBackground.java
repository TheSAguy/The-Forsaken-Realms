package forge.adventure.stage;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.math.GridPoint2;
import com.badlogic.gdx.math.Rectangle;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.utils.Array;
import forge.adventure.pointofintrest.PointOfInterest;
import forge.adventure.world.World;
import forge.adventure.world.WorldSave;

import java.util.ArrayList;

/**
 * Background for the over world, will get biome information and create chunks based on the terrain.
 */
public class WorldBackground extends Actor {


    int chunkSize;
    int tileSize;
    int playerX;
    int playerY;

    Texture[][] chunks;
    Texture loadingTexture, t;
    Array<Actor>[][] chunksSprites;
    Array<Actor>[][] chunksSpritesBackground;
    int currentChunkX;
    int currentChunkY;

    GameStage stage;

    // Bonus reveal radius applied around a point of interest the first time the player gets
    // within normal vision range of it - discovering a town uncovers the area around it, not just
    // the tile the player happens to be standing on. Split into two tiers (user spec 2026-08-11,
    // "the dungeon lift radius should not be as big as a town radius"): town/capital/castle POIs
    // keep the original value, everything else (dungeon/cave/sideboss*) gets roughly half.
    private static final int DISCOVERY_REVEAL_RADIUS_TOWN = 11; // 75% of the original 15
    private static final int DISCOVERY_REVEAL_RADIUS_DUNGEON = 6; // ~50% of the town radius

    // Town/capital/castle POIs get the larger discovery radius above; everything else (dungeon,
    // cave, sidebossEasy/Moderate/Hard, etc) gets the smaller one.
    private static boolean isTownLikePoi(PointOfInterest poi) {
        String type = poi.getData().type;
        return "town".equalsIgnoreCase(type) || "capital".equalsIgnoreCase(type) || "castle".equalsIgnoreCase(type);
    }

    // Throttles the per-frame "keep the visible-vs-hazed boundary accurate" repatch below to only
    // run when the player has actually moved to a new tile, not every single rendered frame.
    private int lastVisibilityPatchX = Integer.MIN_VALUE;
    private int lastVisibilityPatchY = Integer.MIN_VALUE;

    public WorldBackground(GameStage gameStage) {
        stage = gameStage;
    }

    public void draw(Batch batch, float parentAlpha) {
        if (chunks == null) {
            initialize();
        }
        World world = WorldSave.getCurrentSave().getWorld();
        int playerTileX = playerX / tileSize;
        int playerTileY = playerY / tileSize;
        int visionRadius = world.getVisionRadius();
        world.revealArea(playerTileX, playerTileY, visionRadius, this::onTileRevealed);
        world.setPlayerTilePosition(playerTileX, playerTileY);
        // Discovery-flash decay (see World.tickTemporaryReveals()'s own comment) - cheap no-op
        // whenever nothing is currently flashing, which is nearly always.
        world.tickTemporaryReveals(Gdx.graphics.getDeltaTime(), this::onTileRevealed);

        // Known tiles near the player toggle between hazed and fully visible as they walk, even
        // when nothing newly becomes known - re-patch the local neighborhood (one tile wider than
        // the vision radius, to also catch tiles that just left it) whenever the player's tile
        // position changes.
        if (playerTileX != lastVisibilityPatchX || playerTileY != lastVisibilityPatchY) {
            lastVisibilityPatchX = playerTileX;
            lastVisibilityPatchY = playerTileY;
            int patchRadius = visionRadius + 1;
            for (int tx = playerTileX - patchRadius; tx <= playerTileX + patchRadius; tx++) {
                for (int ty = playerTileY - patchRadius; ty <= playerTileY + patchRadius; ty++) {
                    onTileRevealed(tx, ty);
                }
            }
        }

        GridPoint2 pos = translateFromWorldToChunk(playerX, playerY);
        // 3x3 chunk neighborhood, not just the player's own chunk (2026-08-15 bug fix, user
        // report: "able to enter the town without it revealing"). A POI registers under the chunk
        // containing its bottom-left corner, but its SPRITE (and therefore its collision box) is
        // loaded from the whole 3x3 loaded-chunk neighborhood - so a town whose footprint crossed
        // a chunk boundary could be physically entered from the neighboring chunk while this
        // reveal loop, which only iterated the player's own chunk, never saw it. Same 3x3
        // coverage the collision path effectively has.
        java.util.List<PointOfInterest> nearbyPois = new ArrayList<>();
        for (int cdx = -1; cdx <= 1; cdx++)
            for (int cdy = -1; cdy <= 1; cdy++)
                nearbyPois.addAll(world.getPointsOfInterest(pos.x + cdx, pos.y + cdy));
        for (PointOfInterest poi : nearbyPois) {
            // Dungeon rotation (MOD_SCOPE.md #15) overprovisions rotatable dungeons/caves 5x and
            // holds most of them inactive as a reserve pool with nothing actually there yet (see
            // DungeonRotation.java) - user report 2026-08-11: fog was lifting near those empty
            // reserve spots exactly like a real, currently-active dungeon. getActive() is false
            // for a reserve slot (and for nothing else - every non-rotating POI type is always
            // active), so skipping inactive POIs here fixes it with no effect on towns/capitals.
            if (!poi.getActive())
                continue;
            // Proximity gated on distance to the NEAREST EDGE of the POI's footprint, not its
            // center (2026-08-11, second pass - the center-distance version from the first
            // playtest round turned out still inconsistent: "have to approach the town from just
            // the exact angle... maybe create a radius around the town to trigger" - exactly
            // right. A large town/capital sprite's center can be several tiles from an edge the
            // player is standing right next to, so gating on center distance meant the trigger
            // radius effectively shrank or grew depending on which side you approached from and
            // how big that particular POI's footprint is. Clamping the player's position into the
            // rectangle first (standard closest-point-on-rect-to-point) gives 0 distance anywhere
            // inside/touching the footprint and a true edge distance outside it - consistent from
            // every approach angle, and reduces to the exact same math as before for a 1-tile
            // dungeon icon (rect ≈ a point).
            Rectangle bounds = poi.getBoundingRectangle();
            float nearestWorldX = Math.max(bounds.x, Math.min(playerX, bounds.x + bounds.width));
            float nearestWorldY = Math.max(bounds.y, Math.min(playerY, bounds.y + bounds.height));
            float dxTiles = (playerX - nearestWorldX) / tileSize;
            float dyTiles = (playerY - nearestWorldY) / tileSize;
            int poiTileX = (int) ((bounds.x + bounds.width / 2f) / tileSize);
            int poiTileY = (int) ((bounds.y + bounds.height / 2f) / tileSize);
            if (dxTiles * dxTiles + dyTiles * dyTiles <= visionRadius * visionRadius) {
                // Discovery flash (user spec 2026-08-09): the burst of tiles a town/capitol
                // uncovers on first approach should flare fully bright for a moment before
                // settling to the normal dimmed "explored" tier, instead of jumping straight
                // there. revealArea()'s callback only fires for tiles that were NOT already
                // explored, so this only flags the genuinely newly-discovered ring - already-
                // explored tiles near a POI (e.g. re-approaching a known town) don't re-flash.
                int discoveryRadius = isTownLikePoi(poi) ? DISCOVERY_REVEAL_RADIUS_TOWN : DISCOVERY_REVEAL_RADIUS_DUNGEON;
                world.revealArea(poiTileX, poiTileY, discoveryRadius, (tx, ty) -> {
                    world.temporarilyReveal(tx, ty);
                    onTileRevealed(tx, ty);
                });
            }
        }
        if (currentChunkX != pos.x || currentChunkY != pos.y) {
            int xDiff = currentChunkX - pos.x;
            int yDiff = currentChunkY - pos.y;
            ArrayList<GridPoint2> points = new ArrayList<GridPoint2>();
            for (int x = -1; x < 2; x++) {
                for (int y = -1; y < 2; y++) {
                    points.add(new GridPoint2(pos.x + x, pos.y + y));
                }
            }
            for (int x = -1; x < 2; x++) {
                for (int y = -1; y < 2; y++) {
                    GridPoint2 point = new GridPoint2(currentChunkX + x, currentChunkY + y);
                    if (points.contains(point))// old Point is part of new points
                    {
                        points.remove(point);
                    } else {
                        if (point.y < 0 || point.x < 0 || point.y >= chunks[0].length || point.x >= chunks.length)
                            continue;
                        unLoadChunk(point.x, point.y);
                    }
                }
            }
            for (GridPoint2 point : points) {
                if (point.y < 0 || point.x < 0 || point.y >= chunks[0].length || point.x >= chunks.length)
                    continue;
                loadChunk(point.x, point.y);
            }
            currentChunkX = pos.x;
            currentChunkY = pos.y;
        }
        for (int x = -1; x < 2; x++) {
            for (int y = -1; y < 2; y++) {
                if (pos.y + y < 0 || pos.x + x < 0 || pos.y >= chunks[0].length || pos.x >= chunks.length)
                    continue;


                batch.draw(getChunkTexture(pos.x + x, pos.y + y), transChunkToWorld(pos.x + x), transChunkToWorld(pos.y + y));
            }
        }

    }

    public void loadChunk(int x, int y) {
        if (chunksSprites[x][y] == null)
            chunksSprites[x][y] = MapSprite.getMapSprites(x, y, MapSprite.SpriteLayer);

        for (Actor sprite : chunksSprites[x][y]) {
            stage.getSpriteGroup().addActor(sprite);
        }
        if (chunksSpritesBackground[x][y] == null)
            chunksSpritesBackground[x][y] = MapSprite.getMapSprites(x, y, MapSprite.BackgroundLayer);
        for (Actor sprite : chunksSpritesBackground[x][y]) {
                stage.getBackgroundSprites().addActor(sprite);
        }
    }

    private void unLoadChunk(int x, int y) {
        Array<Actor> sprites = chunksSprites[x][y];
        if (sprites != null) {
            for (Actor sprite : sprites) {
                stage.getSpriteGroup().removeActor(sprite);
            }
        }
        sprites = chunksSpritesBackground[x][y];
        if (sprites != null) {
            for (Actor sprite : sprites) {
                stage.getBackgroundSprites().removeActor(sprite);
            }
        }
    }

    public Texture getChunkTexture(int x, int y) {
        Texture tex = chunks[x][y];
        if (tex == null) {
            Texture newChunk = new Texture(chunkSize * tileSize, chunkSize * tileSize, Pixmap.Format.RGBA8888);
            for (int cx = 0; cx < chunkSize; cx++) {
                for (int cy = 0; cy < chunkSize; cy++) {
                    newChunk.draw(WorldSave.getCurrentSave().getWorld().getBiomeSprite(cx + chunkSize * x, cy + chunkSize * y), cx * tileSize, (chunkSize * tileSize) - (cy + 1) * tileSize);
                }
            }
            chunks[x][y] = newChunk;
        }
        return chunks[x][y];
    }

    public void initialize() {
        tileSize = WorldSave.getCurrentSave().getWorld().getTileSize();
        chunkSize = WorldSave.getCurrentSave().getWorld().getChunkSize();
        if (chunks != null) {
            stage.getSpriteGroup().clear();
            for (Texture[] chunk : chunks)
                for (Texture texture : chunk)
                    if (texture != null)
                        texture.dispose();
        }
        chunks = new Texture[WorldSave.getCurrentSave().getWorld().getWidthInTiles()][WorldSave.getCurrentSave().getWorld().getHeightInTiles()];
        Array[][] createChunks = new Array[WorldSave.getCurrentSave().getWorld().getWidthInTiles()][WorldSave.getCurrentSave().getWorld().getHeightInTiles()];
        chunksSprites = createChunks;
        Array[][] createSprites = new Array[WorldSave.getCurrentSave().getWorld().getWidthInTiles()][WorldSave.getCurrentSave().getWorld().getHeightInTiles()];
        chunksSpritesBackground = createSprites;


        if (loadingTexture == null) {
            Pixmap loadPix = new Pixmap(chunkSize * tileSize, chunkSize * tileSize, Pixmap.Format.RGBA8888);
            loadPix.setColor(0.5f, 0.5f, 0.5f, 1);
            loadPix.fill();
            loadingTexture = new Texture(loadPix);
        }


        for (int x = -1; x < 2; x++) {
            for (int y = -1; y < 2; y++) {
                GridPoint2 point = new GridPoint2(currentChunkX + x, currentChunkY + y);
                if (point.y < 0 || point.x < 0 || point.y >= chunks[0].length || point.x >= chunks.length)
                    continue;
                loadChunk(point.x, point.y);
            }
        }
    }

    @Override
    public void clear() {
        super.clear();
        initialize();
    }

    int transChunkToWorld(int xy) {
        return xy * tileSize * chunkSize;
    }

    GridPoint2 translateFromWorldToChunk(float x, float y) {
        float worldWidthTiles = x / tileSize;
        float worldHeightTiles = y / tileSize;
        return new GridPoint2((int) worldWidthTiles / chunkSize, (int) worldHeightTiles / chunkSize);
    }

    public void setPlayerPos(float x, float y) {

        playerX = (int) x;
        playerY = (int) y;
    }

    // Forces a chunk's decoration Actors (rocks/flowers/etc, from World.mapObjectIds) to refresh
    // from current data - unlike ground textures, loadChunk()/unLoadChunk() alone don't do this:
    // chunksSprites/chunksSpritesBackground are only populated once and cached, so a plain
    // unload+reload would just re-add the same stale Actor list. Used by World.repaintBiomeAroundTown()
    // after it regenerates mapObjectIds for a repainted area, via WorldStage's bridge.
    void reloadChunkObjects(int chunkX, int chunkY) {
        if (chunksSprites == null || chunkX < 0 || chunkY < 0 || chunkX >= chunksSprites.length || chunkY >= chunksSprites[0].length)
            return;
        if (chunksSprites[chunkX][chunkY] == null && chunksSpritesBackground[chunkX][chunkY] == null)
            return; // never loaded - will pick up fresh data whenever it does load, nothing to do now
        unLoadChunk(chunkX, chunkY);
        chunksSprites[chunkX][chunkY] = null;
        chunksSpritesBackground[chunkX][chunkY] = null;
        // Only re-ADD the rebuilt actors when this chunk is inside the live 3x3 window
        // (2026-08-26 perf/correctness fix, part of the day-end freeze): the unconditional
        // loadChunk() here ADDED an off-screen chunk's actors straight into the live stage
        // groups - territory expansion reloads dozens of far-away chunks every day, so the stage
        // accumulated (and double-added, once the player later walked back in) thousands of
        // orphan actors over a session, growing every frame's act/draw cost the longer a
        // playthrough ran. Off-window chunks now just invalidate their cache; the ordinary
        // window-transition loadChunk() rebuilds them fresh whenever the player next approaches.
        if (Math.abs(chunkX - currentChunkX) <= 1 && Math.abs(chunkY - currentChunkY) <= 1)
            loadChunk(chunkX, chunkY);
    }

    // Called when a tile newly becomes explored, or (package-private, see WorldStage's
    // refreshBackgroundTile bridge) when a tile's terrain was repainted at runtime, e.g. by
    // World.repaintBiomeAroundTown(). If its chunk texture is already built, patch just that
    // tile in place (mirroring the coordinate math in getChunkTexture) instead of rebuilding the
    // whole chunk.
    void onTileRevealed(int worldTileX, int worldTileY) {
        if (chunks == null)
            return;
        int chunkX = Math.floorDiv(worldTileX, chunkSize);
        int chunkY = Math.floorDiv(worldTileY, chunkSize);
        if (chunkX < 0 || chunkY < 0 || chunkX >= chunks.length || chunkY >= chunks[0].length)
            return;
        Texture tex = chunks[chunkX][chunkY];
        if (tex == null)
            return; // chunk not built yet; it will draw correctly once it is, since getBiomeSprite() checks explored state
        // Off-window chunks EVICT instead of patching (2026-08-26 perf fix, part of the day-end
        // freeze): each patch runs a full getBiomeSprite() neighbor-blend composition plus a GPU
        // texture upload, and daily territory expansion repaints 1000+ tiles/day, almost all in
        // chunks nowhere near the player - built once, kept forever, patched tile-by-tile purely
        // so they'd be correct IF revisited. Disposing the stale texture is near-free now, and
        // getChunkTexture() rebuilds it on demand the next time it actually enters the window -
        // the exact same lazy path a never-yet-visited chunk already takes.
        if (Math.abs(chunkX - currentChunkX) > 1 || Math.abs(chunkY - currentChunkY) > 1) {
            tex.dispose();
            chunks[chunkX][chunkY] = null;
            return;
        }
        int localX = Math.floorMod(worldTileX, chunkSize);
        int localY = Math.floorMod(worldTileY, chunkSize);
        Pixmap tile = WorldSave.getCurrentSave().getWorld().getBiomeSprite(worldTileX, worldTileY);
        tex.draw(tile, localX * tileSize, (chunkSize * tileSize) - (localY + 1) * tileSize);
    }
}
