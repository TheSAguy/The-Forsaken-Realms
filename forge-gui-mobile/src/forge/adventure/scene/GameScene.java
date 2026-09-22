package forge.adventure.scene;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.math.Vector2;
import forge.Forge;
import forge.adventure.data.BiomeData;
import forge.adventure.pointofintrest.PointOfInterest;
import forge.adventure.stage.MapStage;
import forge.adventure.stage.WorldStage;
import forge.adventure.util.Config;
import forge.adventure.util.Current;
import forge.adventure.world.World;
import forge.util.TextUtil;

import java.util.HashMap;
import java.util.List;

/**
 * Game scene main over world scene
 * does render the WorldStage and HUD
 */
public class GameScene extends HudScene {
    public GameScene() {
        super(WorldStage.getInstance());
    }

    private static GameScene object;
    private String location = "";
    private String locationColorID = "[+c]";
    private static final HashMap<String, String> cachedHeaderNamesMap = new HashMap<>();
    private static final HashMap<String, String> cachedColorIDsMap = new HashMap<>();

    static {
        // Round 178 (user request): "player" carries the TFR medallion on the player's own land.
        // Round 289 moved it into upstream's 09.22 lookup table - the switch it used to live in was
        // replaced by this cache, whose stock six biomes would have dropped the medallion to "[+c]".
        // The generic branch below also gives "player" the same "Player Map" header the old
        // TextUtil.capitalize(name) + " Map" produced, so the header text is unchanged too.
        String[] colors = {"white", "red", "green", "blue", "black", "waste", "player"};
        String[] colorTags = {"[+w]", "[+r]", "[+g]", "[+u]", "[+b]", "[+c]", "[+tfr]"};

        for (int i = 0; i < colors.length; i++) {
            String name = colors[i];
            cachedColorIDsMap.put(name, colorTags[i]);

            if ("waste".equals(name)) {
                cachedHeaderNamesMap.put(name, "Waste Map");
            } else {
                cachedHeaderNamesMap.put(name, name.substring(0, 1).toUpperCase() + name.substring(1) + " Map");
            }
        }
    }

    public static GameScene instance() {
        if (object == null)
            object = new GameScene();
        return object;
    }

    @Override
    public void dispose() {
        stage.dispose();
    }

    @Override
    public void act(float delta) {
        stage.act(delta);
    }

    @Override
    public void render() {
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);
        stage.draw();
        hud.draw();
    }

    @Override
    public void enter() {
        MapStage.getInstance().clearIsInMap();
        // Leaving for the world map means no town context, so sell prices stop inheriting the
        // last town's haggling reputation (2026-08-31 - see AdventurePlayer
        // .setCurrentLocationChanges). null yields a neutral 1.0f multiplier.
        Current.player().setCurrentLocationChanges(null);
        Forge.clearTransitionScreen();
        Forge.clearCurrentScreen();
        super.enter();
        // Standalone welcome popup (MOD_SCOPE.md #89): once per save, on first world-map entry
        // - a new game lands here right after the tutorial teleport, and no auto quest dialog
        // competes on the world map (the spawn-dungeon attempts both collided with the intro
        // dialog, see TileMapScene.initializeDialogs()). Config-driven; stock planes never set
        // welcomePopupText, so nothing changes for them.
        String welcome = Config.instance().getConfigData().welcomePopupText;
        if (welcome != null && !welcome.isEmpty() && !Current.player().checkQuestFlag("TFR_WelcomeShown")) {
            Current.player().setQuestFlag("TFR_WelcomeShown", 1);
            WorldStage.getInstance().showWelcomeDialog(welcome);
        }
        // This causes the infinite load of POI if the two collision point is too close.
        // IIRC This is used before and the player will start inside the POI.
        // but we don't allow saving inside the POI anymore.
        // WorldStage.getInstance().handlePointsOfInterestCollision();
    }

    public String getLocationColorID() {
        return locationColorID;
    }

    // updateBGM is inside act method so this is polled every frame. I wonder how to optimize this further
    public String getAdventurePlayerLocation(boolean forHeader, boolean skipRoads) {
        if (MapStage.getInstance().isInMap()) {
            location = forHeader ? TileMapScene.instance().rootPoint.getDisplayName() : TileMapScene.instance().rootPoint.getData().type;
        } else {
            World world = Current.world();
            int tileSize = world.getTileSize();

            int playerTileX = (int) stage.getPlayerSprite().getX() / tileSize;
            int playerTileY = (int) stage.getPlayerSprite().getY() / tileSize;

            // this gets the name of the layer... this shoud be based on boundaries...
            int currentBiome = World.highestBiome(world.getBiomeMapXY(playerTileX, playerTileY));
            List<BiomeData> biomeData = world.getData().GetBiomes();

            if (biomeData.size() <= currentBiome) { // shouldn't be the case but default to waste
                if (skipRoads) {
                    location = forHeader ? cachedHeaderNamesMap.get("waste") : "waste";
                } else {
                    location = "";
                }
                locationColorID = cachedColorIDsMap.get("waste");
            } else {
                BiomeData data = biomeData.get(currentBiome);
                String biomeName = data.name != null ? data.name : "waste";

                if (forHeader) {
                    String cachedHeader = cachedHeaderNamesMap.get(biomeName);
                    if (cachedHeader == null) {
                        // fallback mapping
                        cachedHeader = TextUtil.capitalize(biomeName) + " Map";
                        cachedHeaderNamesMap.put(biomeName, cachedHeader);
                    }
                    location = cachedHeader;
                } else {
                    location = biomeName;
                }

                String cachedColor = cachedColorIDsMap.get(biomeName);
                locationColorID = cachedColor != null ? cachedColor : "[+c]";
            }
        }
        return location;
    }

    public String getBiomeByPosition(Vector2 position) {
        World world = Current.world();
        int currentBiome = World.highestBiome(world.getBiomeMapXY((int) position.x / world.getTileSize(), (int) position.y / world.getTileSize()));
        List<BiomeData> biomeData = world.getData().GetBiomes();
        return biomeData.size() <= currentBiome ? "waste" : biomeData.get(currentBiome).name; //shouldn't be the case but default to waste
    }

    public PointOfInterest getMapPOI() {
        if (MapStage.getInstance().isInMap()) {
            return TileMapScene.instance().rootPoint;
        }
        return null;
    }
}

