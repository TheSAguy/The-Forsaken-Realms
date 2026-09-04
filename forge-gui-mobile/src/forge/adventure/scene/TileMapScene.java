package forge.adventure.scene;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.maps.tiled.TiledMap;
import com.google.common.collect.Lists;
import forge.Forge;
import forge.adventure.pointofintrest.PointOfInterest;
import forge.adventure.pointofintrest.PointOfInterestChanges;
import forge.adventure.stage.MapStage;
import forge.adventure.stage.PointOfInterestMapRenderer;
import forge.adventure.stage.WorldStage;
import forge.adventure.util.*;
import forge.adventure.world.WorldSave;
import forge.sound.SoundEffectType;
import forge.sound.SoundSystem;

import java.util.ArrayList;

/**
 * Scene that will render tiled maps.
 * Used for towns dungeons etc
 */
public class TileMapScene extends HudScene {
    TiledMap map;
    PointOfInterestMapRenderer tiledMapRenderer;
    private String nextMap;
    private int nextSpawnPoint;
    private boolean autoheal = false;

    private TileMapScene() {
        super(MapStage.getInstance());
        tiledMapRenderer = new PointOfInterestMapRenderer((MapStage) stage);

        //set initial camera width and height
        MapStage.getInstance().setDialogStage(hud);
    }

    private static TileMapScene object;

    public static TileMapScene instance() {
        if (object == null)
            object = new TileMapScene();
        return object;
    }

    public MapStage currentMap() {
        return (MapStage) stage;
    }

    @Override
    public void dispose() {
        if (map != null)
            map.dispose();
    }

    @Override
    public void act(float delta) {
        if (map == null)
            return;
        if (nextMap != null) {
            String target = nextMap;
            int spawn = nextSpawnPoint;
            nextMap = null;
            nextSpawnPoint = 0;
            try {
                load(target, spawn);
            } catch (Exception e) {
                System.err.println("Error loading map " + target + "...");
                e.printStackTrace();
                MapStage.getInstance().exitDungeon(false, false);
            }
        }
        stage.act(Gdx.graphics.getDeltaTime());
        hud.act(Gdx.graphics.getDeltaTime());
        if (autoheal) {
            stage.getPlayerSprite().playEffect(Paths.EFFECT_HEAL, 2);
            SoundSystem.instance.play(SoundEffectType.Enchantment, false);
            autoheal = false;
        }
    }

    @Override
    public void render() {
        if (map == null)
            return;
        Gdx.gl.glClearColor(0, 0, 0, 1);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);

        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        tiledMapRenderer.setView(stage.getCamera().combined, stage.getCamera().position.x - Scene.getIntendedWidth() / 2.0f, stage.getCamera().position.y - Scene.getIntendedHeight() / 2.0f, Scene.getIntendedWidth(), Scene.getIntendedHeight());

        if (!Forge.isLandscapeMode()) {
            stage.getCamera().position.x = stage.getPlayerSprite().pos().x;
        }
        tiledMapRenderer.render();
        hud.draw();
    }


    @Override
    public void enter() {
        super.enter();
        if (isAutoHealLocation()) {
            // Color reputation (MOD_SCOPE.md #1): entering a Partner-tier color's town/capital
            // (not player-owned - those match no color, see ColorReputation.colorOfTown()) grants
            // a free overheal to maxLife+2; entering any other town/capital (including a
            // player-owned one) drops an unused grant back down to maxLife - "lose it if you
            // don't use it before your next duel" (also cleared in DuelScene.GameEnd()).
            String repColor = ColorReputation.colorOfTown(rootPoint.getData());
            boolean playerOwned = repColor != null && TownRestoration.isTownRestored(
                    WorldSave.getCurrentSave().peekPointOfInterestChanges(rootPoint.getID()));
            // The base free heal below used to be unconditional - user report 2026-08-11: "still
            // getting life restored when visiting a town... unhappy/at war with" (the reputation
            // check further down only ever governed the Partner BONUS, never the base heal itself
            // - see ColorReputation.isFreeHealBlocked()'s own comment). Player-owned towns are
            // exempt from every color effect, same as everywhere else in this system.
            boolean healBlocked = repColor != null && !playerOwned && ColorReputation.isFreeHealBlocked(repColor);
            if (!healBlocked && Current.player().fullHeal())
                autoheal = true; // to play sound/effect on act
            if (repColor != null && !playerOwned && ColorReputation.getStatus(repColor) == ColorReputation.Status.PARTNER)
                Current.player().grantPartnerOverheal();
            else
                Current.player().clearPartnerOverhealIfActive();
        }
        if (WorldSave.getCurrentSave().getPlayer().hasAnnounceFantasy()) {
            WorldSave.getCurrentSave().getPlayer().clearAnnounceFantasy();
            MapStage.getInstance().showDeckAwardDialog("{BLINK=WHITE;RED}" +
                Forge.getLocalizer().getMessage("lblMode") + " " +
                Forge.getLocalizer().getMessage("lblChaos") + "{ENDBLINK}\n" +
                Forge.getLocalizer().getMessage("lblChaosModeDescription"),
                WorldSave.getCurrentSave().getPlayer().getSelectedDeck(), this::initializeDialogs);
        } else if (WorldSave.getCurrentSave().getPlayer().hasAnnounceCustom()) {
            WorldSave.getCurrentSave().getPlayer().clearAnnounceCustom();
            MapStage.getInstance().showDeckAwardDialog("{GRADIENT}" +
                Forge.getLocalizer().getMessage("lblMode") + " " +
                Forge.getLocalizer().getMessage("lblCustom") + "{ENDGRADIENT}\n" +
                Forge.getLocalizer().getMessage("lblCustomModeDescription"),
                WorldSave.getCurrentSave().getPlayer().getSelectedDeck(), this::initializeDialogs);
        } else {
            initializeDialogs();
        }
    }

    private void initializeDialogs() {
        // The standalone welcome popup used to hook in here (first POI entry) - relocated to
        // GameScene.enter()/WorldStage.showWelcomeDialog() 2026-08-20 after two collisions:
        // shown here it was immediately replaced by the tutorial intro dialog, and a
        // "Welcome" option grafted into that quest dialog soft-locked the tutorial (an
        // option with no follow-up node strands the dialog before Intro/Skip can set
        // mainQuest, leaving the spawn portal inactive). The world map has no competing
        // auto-dialogs, and every new game reaches it right after the tutorial teleport.
        AdventureQuestController.instance().updateEnteredPOI(rootPoint);
        AdventureQuestController.instance().showQuestDialogs(stage);
    }
    @Override
    public boolean leave() {
        // clear player collision on WorldStage and the GameHUD will restore it after the flicker animation.
        // There's at least 2 seconds to get away from problematic collision point and player can retry
        // a few times to move to different position if the POI is loaded again from WorldStage
        WorldStage.getInstance().getPlayerSprite().clearCollisionHeight();
        return super.leave();
    }

    public void load(PointOfInterest point) {
        AdventureQuestController.instance().mostRecentPOI = point;
        if (rootPoint != point) {
            // If we go from one town to another, don't resume the previous track.
            SoundSystem.instance.clearShelvedPlaylist();
        }
        rootPoint = point;
        // Main-quest hooks (2026-08-26, "Where Am I?" tutorial extension): "ruined" vs
        // "surviving" wasteland town is pure RUNTIME state (identical POI templates/tags), so a
        // Travel objective can't distinguish them - the distinction only exists here, at actual
        // map entry, via the same TownRestoration checks the town interior itself uses.
        // setCharacterFlag re-fires harmlessly on every entry (idempotent value, quest stages
        // complete once); guarded so a fresh save's spawn-dungeon entry can't NPE anything.
        try {
            if (forge.adventure.util.TerritoryControl.isRingTown(point)) { // round 105: quest 75's stages complete on ENTERING a Ring City
                String ringName = point.getData().name;
                char arm = ringName.charAt(ringName.length() - 1);
                Current.player().setCharacterFlag("enteredRingCity" + arm, 1);
                System.out.println("[TFR-MainQuest] enteredRingCity" + arm + " -> 1 (" + point.getDisplayName() + ")");
            }
            if (TownRestoration.isWastelandTown(point.getData())) {
                forge.adventure.pointofintrest.PointOfInterestChanges entryChanges =
                        WorldSave.getCurrentSave().getPointOfInterestChanges(point.getID());
                if (TownRestoration.isNeutralSeededTown(entryChanges)) {
                    Current.player().setCharacterFlag("enteredSurvivingTown", 1);
                    System.out.println("[TFR-MainQuest] enteredSurvivingTown -> 1 (" + point.getDisplayName() + ")");
                } else if (!TownRestoration.isTownRestored(entryChanges)) {
                    Current.player().setCharacterFlag("enteredRuinedTown", 1);
                    System.out.println("[TFR-MainQuest] enteredRuinedTown -> 1 (" + point.getDisplayName() + ")");
                }
            }
        } catch (Exception e) {
            e.printStackTrace(); // never let a tutorial convenience break map loading
        }
        oldMap = point.getData().map;
        map = new TemplateTmxMapLoader().load(Config.instance().getCommonFilePath(point.getData().map));
        ((MapStage) stage).setPointOfInterest(getPointOfInterestChanges());
        // Sell prices follow the town you are STANDING IN (2026-08-31 fix). This field previously
        // had exactly one writer - the Inn Sell Cards button - and was never cleared, so every
        // quoted sell price came from whichever shop screen was opened last, in whatever town, and
        // survived a save load. Two callers spend real gold on it: the ante Buy Back charge and
        // the auto-sell payout on a Loot reward screen, which is where Inn tournament prizes land.
        Current.player().setCurrentLocationChanges(getPointOfInterestChanges());
        stage.getPlayerSprite().setPosition(0, 0);
        WorldSave.getCurrentSave().getWorld().setSeed(point.getSeedOffset());
        tiledMapRenderer.loadMap(map, "", oldMap, 0);
        stage.getPlayerSprite().stop();
    }

    private final static ArrayList<String> AUTO_HEAL_LOCATIONS = Lists.newArrayList("capital", "town");

    public boolean isAutoHealLocation() {
        return AUTO_HEAL_LOCATIONS.contains(rootPoint.getData().type);
    }

    public PointOfInterest rootPoint;
    String oldMap;

    private void load(String targetMap, int nextSpawnPoint) {
        map = new TemplateTmxMapLoader().load(Config.instance().getFilePath(targetMap));
        ((MapStage) stage).setPointOfInterest(getPointOfInterestChanges(targetMap));
        stage.getPlayerSprite().setPosition(0, 0);
        WorldSave.getCurrentSave().getWorld().setSeed(rootPoint.getSeedOffset());
        tiledMapRenderer.loadMap(map, oldMap, targetMap, nextSpawnPoint);
        oldMap = targetMap;
        stage.getPlayerSprite().stop();
    }

    public PointOfInterestChanges getPointOfInterestChanges() {
        return WorldSave.getCurrentSave().getPointOfInterestChanges(rootPoint.getID());
    }

    public PointOfInterestChanges getPointOfInterestChanges(String targetMap) {
        if (rootPoint.getID().endsWith(targetMap))
            return getPointOfInterestChanges();
        return WorldSave.getCurrentSave().getPointOfInterestChanges(rootPoint.getID() + targetMap);
    }


    @Override
    public boolean isInHudOnlyMode() {
        return MapStage.getInstance().isDialogOnlyInput();
    }

    public void loadNext(String targetMap, int entryTargetObject) {
        nextMap = targetMap;
        nextSpawnPoint = entryTargetObject;
    }
}

