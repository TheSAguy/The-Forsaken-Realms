package forge.adventure.stage;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.Animation;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.graphics.g2d.Sprite;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.math.GridPoint2;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Rectangle;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.ui.Dialog;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.Timer;
import com.badlogic.gdx.utils.viewport.Viewport;
import com.github.tommyettinger.textra.TextraButton;
import com.github.tommyettinger.textra.TypingLabel;
import forge.Forge;
import forge.OverlayText;
import forge.adventure.character.CharacterSprite;
import forge.adventure.character.EnemySprite;
import forge.adventure.data.*;
import forge.adventure.pointofintrest.PointOfInterest;
import forge.adventure.scene.DuelScene;
import forge.adventure.scene.InfoTextScene;
import forge.adventure.scene.GameScene;
import forge.adventure.scene.RewardScene;
import forge.adventure.scene.Scene;
import forge.adventure.scene.StartScene;
import forge.adventure.scene.TileMapScene;
import forge.adventure.util.*;
import forge.adventure.world.World;
import forge.adventure.world.WorldSave;
import forge.gui.FThreads;
import forge.haptic.HapticEngine;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.screens.TransitionScreen;
import forge.sound.SoundEffectType;
import forge.sound.SoundSystem;
import forge.util.MyRandom;
import forge.util.ScreenUtil;
import org.apache.commons.lang3.tuple.Pair;

import java.util.*;


/**
 * Stage for the over world. Will handle monster spawns
 */
public class WorldStage extends GameStage implements SaveFileContent {
    private static WorldStage instance = null;
    protected EnemySprite currentMob;
    // Capitol defense (MOD_SCOPE.md #7 forced duel, 2026-08-10): true while currentMob is the
    // one-shot mage duel triggered by TerritoryControl.checkPendingCapitolDefense() - losing it
    // ends the run (triggerCapitolDefeat()) instead of the ordinary life/gold-penalty loss path.
    private boolean currentMobIsCapitolDefense = false;
    // Town assault (MOD_SCOPE #87, 2026-09-03): set by startTownAssault(), read once by setWinner()
    // for the [TFR-TownAssault] outcome line. Consequences beyond the ordinary duel win/loss
    // (capture, tiers) are deliberately not built yet - user: "for now, just choose a random enemy".
    private boolean currentMobIsTownAssault = false;
    private String townAssaultTownName = null;
    private PointOfInterest townAssaultPoi = null;
    private String townAssaultColor = null;
    protected Random rand = MyRandom.getRandom();
    WorldBackground background;
    private float spawnDelay = 0;
    private static final float spawnInterval = 4;//todo config
    // Base per-spawn-roll chance of a nearby foreign color's biome overriding this roll (before
    // ColorReputation.getSpawnIntrusionMultiplier() scales it by relationship) - user request
    // 2026-08-10, MOD_SCOPE.md #7 follow-up.
    private static final float SPAWN_INTRUSION_BASE_CHANCE = 0.25f;
    // Colorless/Wasteland mix-in for player territory (user request 2026-08-14): player.json's own
    // roster is a fraction of the size of any color biome's (72 vs 440+, MOD_SCOPE.md #19's own
    // "player territory feels dead" gap only partially closed), so a small, unconditional chance
    // pulls from the Wasteland roster instead - deliberately independent of the foreign-color
    // intrusion mechanism above (no proximity/reputation gating, no diplomatic meaning, just more
    // spawn variety on the player's own land) and much smaller than that mechanism's 25% base, per
    // the user's own framing. Tune here if it feels off in either direction.
    private static final float PLAYER_COLORLESS_MIX_CHANCE = 0.08f;
    private PointOfInterestMapSprite collidingPoint;
    protected ArrayList<Pair<Float, EnemySprite>> enemies = new ArrayList<>();
    private final static Float dieTimer = 20f;//todo config
    private static final float TERRITORY_ARRIVAL_EPSILON = 8f; // MOD_SCOPE.md #7
    private Float globalTimer = 0f;
    private transient boolean enterSpawnPOI = false;

    NavArrowActor navArrow;
    final Rectangle tempBoundingRect = new Rectangle();
    final Vector2 enemyMoveVector = new Vector2();
    boolean collided = false;
    // "Wait" toggle (see GameHUD's wait checkbox): lets time advance while the player stands
    // still, same idea as resting. Cleared automatically the moment the player moves again.
    private boolean waitingForTime = false;
    // Debug "100x Speed" toggle (see GameHUD's speed checkbox) - fast-forwards the day/night
    // clock for testing. Multiplies only the delta passed to advanceTime(), nothing else runs
    // faster (spawns, movement, etc. are unaffected). Was raised from 50x to speed up Territory
    // Control playtesting (MOD_SCOPE.md #7); moved to the new tuning.json 2026-08-14 (user
    // request) and its default put back to 50 there - see TuningData.java. HUD checkbox label
    // renamed the same round from "100x Speed" to "Speed-Up" (en-US.properties lblFastTimeToggle)
    // since the actual multiplier is no longer a fixed, nameable number.
    private boolean fastTimeEnabled = false;
    private static float fastTimeMultiplier() {
        return Config.instance().getTuningData().speedUpMultiplier;
    }

    public WorldStage() {
        super();
        background = new WorldBackground(this);
        addActor(background);
        background.setZIndex(0);
        navArrow = new NavArrowActor();
        addActor(navArrow);
        navArrow.toFront();
    }

    public static WorldStage getInstance() {
        return instance == null ? instance = new WorldStage() : instance;
    }

    public boolean isWaitingForTime() {
        return waitingForTime;
    }

    public void setWaitingForTime(boolean waitingForTime) {
        this.waitingForTime = waitingForTime;
    }

    public boolean isFastTimeEnabled() {
        return fastTimeEnabled;
    }

    // Territory Control (MOD_SCOPE.md #7): the capture mages currently in flight, for map marker
    // overlays. GameHUD's corner minimap reads the protected `enemies` list directly (same
    // package); MapViewScene's zoomed map view lives in the scene package and can't, so this
    // exposes the same territory-mage subset behind a public accessor instead of widening the
    // whole list's visibility.
    public List<EnemySprite> getTerritoryMages() {
        List<EnemySprite> mages = new ArrayList<>();
        for (Pair<Float, EnemySprite> pair : enemies)
            if (pair.getValue().territoryTarget != null)
                mages.add(pair.getValue());
        return mages;
    }

    // Real sparkle animation for resource pickups (Gold: user request 2026-08-09, confirmed
    // against templeofchandra.tmx's stock "Gold" reward object; extended to all 5 types
    // 2026-08-13 with the user's own custom resource_drop.png sheet, replacing the alpha-twinkle
    // fallback below entirely for these types): real frame-by-frame art from each type's own
    // atlas's 4 "Idle" regions via a plain Animation, not a coded fade. Cached per type since
    // Config's own atlas cache isn't guaranteed to exist yet at class-init time; ResourceSpawns.
    // TYPE_* constants index this map directly.
    private static final java.util.Map<Integer, Animation<TextureRegion>> sparkleAnimations = new java.util.HashMap<>();

    private static final java.util.Map<Integer, String> SPARKLE_ATLASES = new java.util.HashMap<>();
    static {
        SPARKLE_ATLASES.put(ResourceSpawns.TYPE_GOLD, Paths.GOLD_ATLAS);
        SPARKLE_ATLASES.put(ResourceSpawns.TYPE_SHARDS, Paths.SHARDS_ATLAS);
        SPARKLE_ATLASES.put(ResourceSpawns.TYPE_WOOD, Paths.WOOD_ATLAS);
        SPARKLE_ATLASES.put(ResourceSpawns.TYPE_STONE, Paths.STONE_ATLAS);
        SPARKLE_ATLASES.put(ResourceSpawns.TYPE_MYSTERY, Paths.MYSTERY_ATLAS);
        SPARKLE_ATLASES.put(ResourceSpawns.TYPE_CHEST, Paths.CHEST_ATLAS);
    }

    // Defensive, not expected in practice: every type above has a real atlas now, so this only
    // ever returns null if an atlas file is somehow missing/unreadable - ResourceSpawnActor
    // falls back to the alpha-twinkle in that case rather than drawing nothing.
    private static Animation<TextureRegion> getSparkleAnimation(int type) {
        if (sparkleAnimations.containsKey(type))
            return sparkleAnimations.get(type);
        Animation<TextureRegion> animation = null;
        String atlasPath = SPARKLE_ATLASES.get(type);
        if (atlasPath != null) {
            com.badlogic.gdx.utils.Array<com.badlogic.gdx.graphics.g2d.TextureAtlas.AtlasRegion> frames =
                    Config.instance().getAtlas(atlasPath).findRegions("Idle");
            if (frames != null && frames.size > 0)
                animation = new Animation<>(0.15f, frames, Animation.PlayMode.LOOP);
        }
        sparkleAnimations.put(type, animation);
        return animation;
    }

    // Random resource spawns (see ResourceSpawns): one lightweight actor per active pickup,
    // rendered inside foregroundSprites so it y-sorts with everything else on the map.
    private static class ResourceSpawnActor extends Actor {
        private final Sprite sprite;
        private final Animation<TextureRegion> sparkleAnimation; // null only if a type's atlas failed to load (see getSparkleAnimation())
        // Per-actor random phase so pickups don't all twinkle in lockstep.
        private final float twinklePhase = MathUtils.random(MathUtils.PI2);
        private float twinkleTime;
        ResourceSpawnActor(Sprite sprite, Animation<TextureRegion> sparkleAnimation) {
            this.sprite = sprite;
            this.sparkleAnimation = sparkleAnimation;
        }
        @Override
        public void act(float delta) {
            super.act(delta);
            twinkleTime += delta;
        }
        @Override
        public void draw(Batch batch, float parentAlpha) {
            // Fog of war (MOD_SCOPE.md #3): pickups are static ground objects, not something that
            // moves - gate on isExploredWorld like MapSprite's POI icons (visible once known/dimmed),
            // not the narrower live-vision-only rule EnemySprite uses. Previously ungated entirely,
            // so pickups rendered fully lit even on solid-black unexplored tiles. Tile coords come
            // from world.getTileSize() like every other fog gate - the first version divided by the
            // actor's own size, which only worked because setSize() happens to use tileSize today.
            World world = Current.world();
            int tileSize = world.getTileSize();
            if (tileSize > 0 && !world.isExploredWorld((int) (getX() / tileSize), (int) (getY() / tileSize)))
                return;
            if (sparkleAnimation != null) {
                // Real drawn animation, no alpha trickery needed - matches templeofchandra.tmx's
                // Gold pickup exactly (same atlas/frames/timing).
                batch.draw(sparkleAnimation.getKeyFrame(twinkleTime, true), getX(), getY(), getWidth(), getHeight());
                return;
            }
            // Gentle alpha twinkle to catch the eye on the overworld - the shared, cached Sprite
            // (Config.getAtlasSprite) is never mutated; only the batch's transient draw color is,
            // and it's restored immediately after so other actors sharing that Sprite are unaffected.
            float alpha = parentAlpha * (0.55f + 0.45f * MathUtils.sin(twinkleTime * 3f + twinklePhase));
            // batch.getColor() returns the batch's *internal* Color by reference, not a copy -
            // snapshot the primitive components before calling setColor, or "restoring" below
            // would just be re-applying the already-mutated object to itself.
            Color prevRef = batch.getColor();
            float pr = prevRef.r, pg = prevRef.g, pb = prevRef.b, pa = prevRef.a;
            batch.setColor(pr, pg, pb, alpha);
            batch.draw(sprite, getX(), getY(), getWidth(), getHeight());
            batch.setColor(pr, pg, pb, pa);
        }
    }

    private final List<Actor> resourceSpawnActors = new ArrayList<>();

    // Clear-and-rebuild sync from World's persisted spawn list (<= ResourceSpawns.MAX_SPAWNS
    // entries, so this is cheap) - called by ResourceSpawns.tick() only when the list actually
    // changed (pickup, expiry, reseed, load), not per frame.
    public void refreshResourceSpawnActors() {
        for (Actor actor : resourceSpawnActors)
            foregroundSprites.removeActor(actor);
        resourceSpawnActors.clear();
        World world = WorldSave.getCurrentSave().getWorld();
        int tileSize = world.getTileSize();
        for (int[] spawn : world.getResourceSpawns()) {
            Sprite sprite = ResourceSpawns.spriteFor(spawn[2]);
            if (sprite == null)
                continue;
            ResourceSpawnActor actor = new ResourceSpawnActor(sprite, getSparkleAnimation(spawn[2]));
            actor.setSize(tileSize, tileSize);
            actor.setPosition(spawn[0] * tileSize, spawn[1] * tileSize);
            foregroundSprites.addActor(actor);
            resourceSpawnActors.add(actor);
        }
    }

    // Bridge for World.repaintBiomeAroundTown()'s onTileRepainted callback - World lives in a
    // different package and shouldn't depend on WorldBackground directly (same reasoning as
    // revealArea's callback), but WorldStage and WorldBackground are the same package so this
    // can reach the package-private patch method directly.
    public void refreshBackgroundTile(int worldTileX, int worldTileY) {
        if (background != null)
            background.onTileRevealed(worldTileX, worldTileY);
    }

    // Bridge for WorldSave's post-load sweep (see its own comment) - forces a chunk's cached
    // ground texture to rebuild from the freshly-loaded biomeMap/terrainMap, same reasoning as
    // reloadBackgroundChunkObjects below but for the separate ground-texture cache.
    public void invalidateBackgroundChunkTexture(int chunkX, int chunkY) {
        if (background != null)
            background.invalidateChunkTexture(chunkX, chunkY);
    }

    // Bridge for World.repaintBiomeAroundTown()'s onChunkNeedsReload callback - same reasoning
    // as refreshBackgroundTile above.
    public void reloadBackgroundChunkObjects(int chunkX, int chunkY) {
        if (background != null)
            background.reloadChunkObjects(chunkX, chunkY);
    }

    public void setFastTimeEnabled(boolean fastTimeEnabled) {
        this.fastTimeEnabled = fastTimeEnabled;
    }

    // Hold-Z run (user spec 2026-08-23): "Hold Z to run at 1.5x speed on the map. This should
    // also affect the time." - a held-key check (Gdx.input.isKeyPressed(), the same per-frame
    // polling API Forge.java/Console.java already use for modifier keys), not an edge-triggered
    // KeyBinding like ordinary movement input - checked live wherever it's needed rather than
    // cached, since "held" is inherently a per-frame question. Z is confirmed unbound elsewhere
    // in adventure mode. Stacks multiplicatively with every other speed source (sprint, road,
    // territory) and with Fast-Time, matching how every other modifier in this method already
    // composes - deliberately not a flat replacement for either.
    public static final float RUN_KEY_SPEED_MULTIPLIER = 1.5f;
    private static boolean isRunKeyHeld() {
        return Gdx.input.isKeyPressed(Input.Keys.Z);
    }

    @Override
    protected void onActing(float delta) {
        if (isPaused() || MapStage.getInstance().isDialogOnlyInput() || Forge.advFreezePlayerControls)
            return;
        drawNavigationArrow();
        if (player.isMoving())
            waitingForTime = false; // moving cancels an active wait
        if (player.isMoving() || waitingForTime) {
            World world = WorldSave.getCurrentSave().getWorld();
            int dayBefore = world.getCurrentDay();
            float timeDelta = fastTimeEnabled ? delta * fastTimeMultiplier() : delta;
            if (isRunKeyHeld())
                timeDelta *= RUN_KEY_SPEED_MULTIPLIER;
            world.advanceTime(timeDelta);
            int dayAfter = world.getCurrentDay();
            if (dayAfter != dayBefore) {
                // [TFR-DayTick] per-subsystem timing (2026-08-26, user request while chasing the
                // recurring day-end stutter: "don't hesitate to add entries to the game log that
                // you can review later") - one line per day-rollover, milliseconds per subsystem,
                // so any future "day-end lag" report can be attributed from forge.log alone
                // instead of re-root-causing from scratch. Costs two nanoTime() reads per
                // subsystem once per in-game day - negligible.
                long tickStart = System.nanoTime();
                EconomyBuildings.processDaysPassed(dayAfter - dayBefore, dayAfter);
                long tEconomy = System.nanoTime();
                TerritoryControl.processDaysPassed(dayAfter - dayBefore, dayAfter);
                long tTerritory = System.nanoTime();
                DungeonRotation.processDaysPassed(dayAfter);
                long tDungeons = System.nanoTime();
                QuestExpiry.processDaysPassed(dayAfter);
                long tQuests = System.nanoTime();
                world.checkFogOfWarStage2(this::refreshBackgroundTile);
                long tFog = System.nanoTime();
                // World Standings line-chart history (2026-08-15) - checked on every real day
                // advance, but recordStandingsHistoryIfNewWeek() itself no-ops unless the week
                // number actually changed, so this doesn't spam a snapshot every single day.
                java.util.Map<String, Integer> standingsCounts = TerritoryControl.getTownCounts(world);
                standingsCounts.remove("Colorless"); // chart is 5 AI colors + Player only
                world.recordStandingsHistoryIfNewWeek(standingsCounts);
                long tickEnd = System.nanoTime();
                System.out.println("[TFR-DayTick] day " + dayAfter
                        + ": economy=" + (tEconomy - tickStart) / 1_000_000 + "ms"
                        + " territory=" + (tTerritory - tEconomy) / 1_000_000 + "ms"
                        + " dungeons=" + (tDungeons - tTerritory) / 1_000_000 + "ms"
                        + " quests=" + (tQuests - tDungeons) / 1_000_000 + "ms"
                        + " fog=" + (tFog - tQuests) / 1_000_000 + "ms"
                        + " standings=" + (tickEnd - tFog) / 1_000_000 + "ms"
                        + " total=" + (tickEnd - tickStart) / 1_000_000 + "ms");
            }
            // Per frame while moving, not just on day change - pickups are walk-over, so the
            // collection check has to track the player's live position (cheap; see its comment).
            ResourceSpawns.tick(world, dayAfter);
            handleMonsterSpawn(delta);
            collided = collided || handlePointsOfInterestCollision();
            globalTimer += delta;
            Iterator<Pair<Float, EnemySprite>> it = enemies.iterator();
            while (it.hasNext()) {
                Pair<Float, EnemySprite> pair = it.next();
                // Territory Control (MOD_SCOPE.md #7): a mage is exempt from the ordinary
                // roaming-monster despawn timer below - getLifetime() defaults to a real-time
                // 20s floor meant for a monster that wanders near the player and should vanish if
                // never engaged, but a mage may need to travel for a long real-world-equivalent
                // time (especially without 10x speed) to reach a distant town. It already has its
                // own, deliberate lifecycle: removed on arrival (TerritoryControl.onMageArrived())
                // or on defeat (the normal path below, unaffected by this check).
                if (pair.getValue().territoryTarget == null && globalTimer >= pair.getKey() + pair.getValue().getLifetime()) {
                    AdventureQuestController.instance().updateDespawn(pair.getValue());
                    AdventureQuestController.instance().showQuestDialogs(MapStage.getInstance());
                    foregroundSprites.removeActor(pair.getValue());
                    it.remove();
                    continue;
                }
                EnemySprite mob = pair.getValue();

                // Territory Control (MOD_SCOPE.md #7): a mage seeks its target town instead of
                // homing toward the player - checked first since it's an unconditional replacement
                // for the whole homing block below, not an addition to it. Still falls through to
                // the ordinary player-collision check afterward, so the player can fight and stop
                // a mage before it arrives - only reaching the town skips that (mage is gone by
                // then, removed here, `continue`s past collision since there's nothing left to hit).
                if (mob.territoryTarget != null) {
                    if (mob.pos().dst(mob.territoryTarget.getPosition()) < TERRITORY_ARRIVAL_EPSILON) {
                        TerritoryControl.onMageArrived(mob);
                        foregroundSprites.removeActor(mob);
                        it.remove();
                        continue;
                    }
                    enemyMoveVector.set(mob.territoryTarget.getPosition()).sub(mob.pos());
                    enemyMoveVector.setLength(mob.speed() * delta);
                    mob.moveBy(enemyMoveVector.x, enemyMoveVector.y);
                } else if (!currentModifications.containsKey(PlayerModification.Hide)) {
                    enemyMoveVector.set(player.getX(), player.getY()).sub(mob.pos());
                    enemyMoveVector.setLength(mob.speed() * delta);
                    tempBoundingRect.set(mob.getX() + enemyMoveVector.x, mob.getY() + enemyMoveVector.y, mob.getWidth(), mob.getHeight() * mob.getCollisionHeight());

                    if (!mob.getData().flying && WorldSave.getCurrentSave().getWorld().collidingTile(tempBoundingRect))//if direct path is not possible
                    {
                        tempBoundingRect.set(mob.getX() + enemyMoveVector.x, mob.getY(), mob.getWidth(), mob.getHeight());
                        if (WorldSave.getCurrentSave().getWorld().collidingTile(tempBoundingRect))//if only x path is not possible
                        {
                            tempBoundingRect.set(mob.getX(), mob.getY() + enemyMoveVector.y, mob.getWidth(), mob.getHeight());
                            if (!WorldSave.getCurrentSave().getWorld().collidingTile(tempBoundingRect))//if y path is possible
                            {
                                mob.moveBy(0, enemyMoveVector.y);
                            }
                        } else {

                            mob.moveBy(enemyMoveVector.x, 0);
                        }
                    } else {
                        mob.moveBy(enemyMoveVector.x, enemyMoveVector.y);
                    }
                }

                // Territory Control (MOD_SCOPE.md #7): a mage already fought (and survived) today
                // can't be re-engaged again until the next in-game day - it just keeps moving.
                boolean mageOnCooldownToday = mob.territoryColor != null && mob.lastDuelDay == world.getCurrentDay();
                if (!mageOnCooldownToday && player.collideWith(mob)) {
                    if (collided)
                        return;
                    collided = true;
                    player.setAnimation(CharacterSprite.AnimationTypes.Attack);
                    player.playEffect(Paths.EFFECT_SPARKS, 0.5f);
                    mob.setAnimation(CharacterSprite.AnimationTypes.Attack);
                    SoundSystem.instance.play(SoundEffectType.Block, false);
                    HapticEngine.vibrate(FPref.UI_VIBRATE_ON_ENEMY_ENCOUNTER, mob.getData().boss ? 400 : 200);
                    Forge.advFreezePlayerControls = true;
                    player.clearCollisionHeight();
                    float attackDuration = Math.max(
                            player.getActionAnimationDuration(CharacterSprite.AnimationTypes.Attack, 0.8f),
                            mob.getActionAnimationDuration(CharacterSprite.AnimationTypes.Attack, 0.8f));
                    startPause(attackDuration, () -> {
                        Forge.setCursor(null, Forge.magnifyToggle ? "1" : "2");
                        SoundSystem.instance.play(SoundEffectType.ManaBurn, false);
                        DuelScene duelScene = DuelScene.instance();
                        FThreads.invokeInEdtNowOrLater(() -> {
                            Forge.setTransitionScreen(new TransitionScreen(() -> {
                                collided = false;
                                duelScene.initDuels(player, mob);
                                Forge.switchScene(duelScene);
                            }, ScreenUtil.getInstance().takeScreenshot(), true, false, false, false, "", Current.player().avatar(), mob.getAtlasPath(), Current.player().getName(), mob.getTieredDisplayName())
                                    .withEnemyStatKey(mob.getName()));
                            currentMob = mob;
                            WorldSave.getCurrentSave().autoSave();
                        });
                    });
                    break;
                }
            }
        } else {
            for (Pair<Float, EnemySprite> pair : enemies) {
                pair.getValue().setAnimation(CharacterSprite.AnimationTypes.Idle);
            }
        }
        collided = false;
    }

    private void removeEnemy(EnemySprite currentMob) {
        currentMob.removeAfterEffects();
        Iterator<Pair<Float, EnemySprite>> it = enemies.iterator();
        while (it.hasNext()) {
            Pair<Float, EnemySprite> pair = it.next();
            if (pair.getValue() == currentMob) {
                it.remove();
                return;
            }
        }
    }

    @Override
    public void setWinner(boolean playerIsWinner, boolean isArena) {
        boolean isCapitolDefense = currentMobIsCapitolDefense;
        currentMobIsCapitolDefense = false;
        final PointOfInterest assaultPoi = currentMobIsTownAssault ? townAssaultPoi : null;
        final String assaultColor = townAssaultColor;
        if (currentMobIsTownAssault) {
            currentMobIsTownAssault = false;
            System.out.println("[TFR-TownAssault] " + townAssaultTownName + " vs " + currentMob.getName()
                    + " -> " + (playerIsWinner ? "WON - town captured" : "LOST") + " (ordinary duel rewards/penalties apply)");
            townAssaultTownName = null;
            townAssaultPoi = null;
            townAssaultColor = null;
        }
        if (playerIsWinner) {
            currentMob.clearCollisionHeight();
            Current.player().win();
            player.setAnimation(CharacterSprite.AnimationTypes.Attack);
            float attackDuration = Math.max(1f,
                    player.getActionAnimationDuration(CharacterSprite.AnimationTypes.Attack, 1f));
            currentMob.playEffect(Paths.EFFECT_BLOOD, 0.5f);
            Timer.schedule(new Timer.Task() {
                @Override
                public void run() {
                    currentMob.setAnimation(CharacterSprite.AnimationTypes.Death);
                    currentMob.resetCollisionHeight();
                    float deathDuration = currentMob.getActionAnimationDuration(CharacterSprite.AnimationTypes.Death, 0.3f);
                    startPause(deathDuration, () -> {
                        Array<Reward> loot = currentMob.getRewards();
                        if (assaultPoi != null) // town assault won: the town changes hands (user spec 2026-09-03)
                            TownRestoration.captureTownForPlayer(Current.world(), assaultPoi, assaultColor);
                        // Bronze Coin ransom reclaim as a visible loot tile (user request
                        // 2026-09-01) - see AdventurePlayer.appendCoinRansomReward. Keyed on the
                        // RAW name, matching what DuelScene stamped the mark with.
                        Current.player().appendCoinRansomReward(loot, currentMob.getName());
                        RewardScene.instance().loadRewards(loot, RewardScene.Type.Loot, null);
                        WorldStage.this.removeEnemy(currentMob);
                        AdventureQuestController.instance().updateQuestsWin(currentMob);
                        AdventureQuestController.instance().showQuestDialogs(MapStage.getInstance());
                        Forge.switchScene(RewardScene.instance());
                        currentMob = null;
                    });
                }
            }, attackDuration);
        } else {
            currentMob.clearCollisionHeight();
            player.setAnimation(CharacterSprite.AnimationTypes.Hit);
            currentMob.setAnimation(CharacterSprite.AnimationTypes.Attack);
            float resultAnimationDuration = Math.max(
                    player.getActionAnimationDuration(CharacterSprite.AnimationTypes.Hit, 0.5f),
                    currentMob.getActionAnimationDuration(CharacterSprite.AnimationTypes.Attack, 0.5f));
            startPause(resultAnimationDuration, () -> {
                currentMob.resetCollisionHeight();
                // Capitol defense (MOD_SCOPE.md #7): losing this one ends the run outright - skip
                // every ordinary loss consequence (life/gold penalty, quest hooks, mage-
                // persistence) entirely, they're meaningless once the game is over.
                if (isCapitolDefense) {
                    currentMob = null;
                    // This path never reaches defeated(), which is what normally consumes the
                    // Bronze Coin gold-loss waiver - drop it here or it survives into the next
                    // loaded game and waives an unrelated defeat (2026-09-01 release review).
                    Current.player().clearSuppressDefeatGoldLoss();
                    triggerCapitolDefeat();
                    return;
                }
                boolean defeated = Current.player().defeated();
                AdventureQuestController.instance().updateQuestsLose(currentMob);
                AdventureQuestController.instance().showQuestDialogs(MapStage.getInstance());
                boolean defeatedFromBoss = currentMob.getData().boss && !isArena;
                // Territory Control (MOD_SCOPE.md #7): losing to an attack mage no longer removes
                // it - it survives and keeps traveling toward its target, just can't be
                // re-engaged again until the next in-game day (see the collision check below and
                // EnemySprite.lastDuelDay). Killing one (the WIN branch above) still removes it.
                if (currentMob.territoryColor != null)
                    currentMob.lastDuelDay = WorldSave.getCurrentSave().getWorld().getCurrentDay();
                else
                    WorldStage.this.removeEnemy(currentMob);
                currentMob = null;
                if (defeated) {
                    WorldStage.getInstance().resetPlayerLocation();
                } else if (defeatedFromBoss) {
                    WorldStage.getInstance().defeatedFromBoss();
                }
            });
        }
    }

    public boolean handlePointsOfInterestCollision() {
        for (Actor actor : foregroundSprites.getChildren()) {
            if (actor.getClass() == PointOfInterestMapSprite.class) {
                PointOfInterestMapSprite point = (PointOfInterestMapSprite) actor;
                if (!point.getPointOfInterest().getActive())
                {
                    continue;
                }
                if (player.collideWith(point.getBoundingRect())) {
                    if (point == collidingPoint) {
                        continue;
                    }
                    // Color reputation (MOD_SCOPE.md #1) severe-tier consequence: this color's
                    // ordinary towns are barred outright; its CAPITALS charge a gold toll
                    // instead (user request - story content lives there, so a hard bar risks
                    // soft-locks). Player-owned towns are exempt (checked inside
                    // entryBarredColor()). Setting collidingPoint reuses the existing
                    // "don't re-trigger while still standing here" mechanism - walk off and
                    // back on to try again (e.g. after earning gold for the toll).
                    String barredColor = entryBarredColor(point.getPointOfInterest());
                    if (barredColor != null) {
                        collidingPoint = point;
                        if ("capital".equals(point.getPointOfInterest().getData().type)) {
                            showCapitalTollDialog(point.getPointOfInterest(), point);
                        } else {
                            // A real blocking dialog, not a passing notification (user request
                            // 2026-08-08: the old corner notification was easy to miss, making the
                            // barred town read as "I just walk through this area" with no
                            // explanation of why it never opens).
                            showEntryBarredDialog(point.getPointOfInterest(), barredColor);
                        }
                        continue;
                    }
                    // Legendary endgame content (2026-08-21, v1.00 feedback "Tier 1"): the
                    // Realm of Legends-ported dungeons (questTag "Legendary") are balanced far
                    // above the surrounding world - warn at the door instead of ambushing.
                    // Same walk-off-and-retry mechanism as the barred/toll dialogs above;
                    // "Enter" replicates the normal entry sequence exactly, like the toll's
                    // pay button does.
                    if (isLegendaryPoi(point.getPointOfInterest())) {
                        collidingPoint = point;
                        showLegendaryWarningDialog(point.getPointOfInterest(), point);
                        continue;
                    }
                    // The loadPOI generates booster and other things that may take time to load, so show a little loading text.
                    OverlayText.getInstance().update("[%240]" + GameScene.instance().getLocationColorID() + "{CAROUSEL} L O A D I N G ");
                    startPause(1f, ()-> {
                        WorldSave.getCurrentSave().autoSave();
                        loadPOI(point.getPointOfInterest());
                        point.getMapSprite().checkOut();
                        WorldSave.getCurrentSave().getPointOfInterestChanges(point.getPointOfInterest().getID()).visit();
                    });
                    return true;
                } else {
                    if (point == collidingPoint) {
                        collidingPoint = null;
                    }
                }
            }
        }
        return false;
    }

    public void loadPOI(PointOfInterest poi) {
        try {
            stop();
            TileMapScene.instance().load(poi);
            TileMapScene.instance().setFromWorldMap(true);
            Forge.switchScene(TileMapScene.instance());
        } catch (Exception e) {
            System.err.println("Error loading map...");
            e.printStackTrace();
        }
    }

    // Color reputation (MOD_SCOPE.md #1): the color whose severe-tier standing bars the player
    // from this POI, or null if entry is fine (not a color's town/capital, standing not severe,
    // or a player-owned town - exempt per explicit user decision).
    private String entryBarredColor(PointOfInterest poi) {
        String color = ColorReputation.colorOfTown(poi.getData());
        if (color == null || !ColorReputation.isEntryBarred(color))
            return null;
        if (TownRestoration.isTownRestored(WorldSave.getCurrentSave().peekPointOfInterestChanges(poi.getID())))
            return null;
        return color;
    }

    private static String capitalizeColor(String color) {
        return Character.toUpperCase(color.charAt(0)) + color.substring(1);
    }

    // Capitol defense (MOD_SCOPE.md #7 forced duel, user request 2026-08-10): called by
    // TerritoryControl.checkPendingCapitolDefense() once it's safe to interrupt (see
    // GameStage.act()). Duels a one-shot CLONE of the arrived mage's EnemyData with
    // gamesPerMatch bumped to 3 - cloned so ordinary mage interceptions elsewhere stay best-of-1
    // (EnemyData.gamesPerMatch already flows straight into DuelScene's match rules, no other
    // plumbing needed). territoryColor carries over onto the clone so DuelScene's mage-kill 2x
    // reputation bonus still applies on a win. Reuses the same transition-screen/initDuels
    // sequence the ordinary player-collision path uses (WorldStage.onActing()), just triggered
    // directly rather than from a live collision.
    /**
     * Town assault (MOD_SCOPE #87, first cut 2026-09-03): fight a random roamer from the town's color
     * pool; the defender starts with its color's basic land on the battlefield, tapped, whoever goes
     * first (EffectData.startBattleWithCardTapped -> RegisteredPlayer -> Player.initVariantsZones).
     * Win/loss then flow through the ordinary setWinner() branches (loot, life/gold penalty).
     * Capture of the town and a defender tier system are the announced follow-ups, not built here.
     */
    public void startTownAssault(PointOfInterest poi, String color) {
        // Guard dots (MOD_SCOPE #87, user spec 2026-09-03): the town's AI guard level picks the
        // defender's tier one step above the dot - none Apprentice, 1 Adept, 2 Master, 3 Archmage,
        // 4 Archmage with two starting lands.
        forge.adventure.pointofintrest.PointOfInterestChanges assaultChanges = WorldSave.getCurrentSave().peekPointOfInterestChanges(poi.getID());
        int guardLevel = assaultChanges == null ? 0 : assaultChanges.getAiGuardLevel();
        String[] tiers = EconomyBuildings.GUARD_TIERS_ASCENDING;
        String defenderTier = tiers[Math.min(guardLevel, tiers.length - 1)];
        int lands = guardLevel >= TerritoryControl.AI_GUARD_MAX_LEVEL ? 2 : 1;
        EnemyData base = TerritoryControl.pickRandomRoamer(Current.world(), color, defenderTier);
        if (base == null) {
            System.out.println("[TFR-TownAssault] no eligible defender in the " + color + " pool for " + poi.getDisplayName());
            GameHUD.getInstance().addNotification("No defenders answer the call at " + poi.getDisplayName() + ".", true);
            return;
        }
        EnemyData duelData = new EnemyData(base);
        EnemySprite defender = new EnemySprite(duelData);
        defender.effect = new EffectData();
        String land = TerritoryControl.basicLandFor(color);
        defender.effect.startBattleWithCardTapped = lands == 2 ? new String[]{land, land} : new String[]{land};
        System.out.println("[TFR-TownAssault] " + poi.getDisplayName() + " guard level " + guardLevel
                + " -> defender tier " + defenderTier + " (" + EnemyData.tierDisplayName(defenderTier) + "), " + lands + " starting land(s)");
        currentMob = defender;
        currentMobIsTownAssault = true;
        townAssaultTownName = poi.getDisplayName();
        townAssaultPoi = poi;
        townAssaultColor = color;
        System.out.println("[TFR-TownAssault] " + poi.getDisplayName() + " (" + color + "): defender "
                + defender.getName() + " (tier=" + duelData.tier + ") starts with a tapped "
                + TerritoryControl.basicLandFor(color));
        Forge.advFreezePlayerControls = true;
        DuelScene duelScene = DuelScene.instance();
        FThreads.invokeInEdtNowOrLater(() -> {
            Forge.setTransitionScreen(new TransitionScreen(() -> {
                Forge.advFreezePlayerControls = false;
                duelScene.initDuels(player, defender);
                Forge.switchScene(duelScene);
            }, ScreenUtil.getInstance().takeScreenshot(), true, false, false, false, "", Current.player().avatar(),
                    defender.getAtlasPath(), Current.player().getName(), defender.getTieredDisplayName())
                    .withEnemyStatKey(defender.getName()));
            WorldSave.getCurrentSave().autoSave();
        });
    }

    public void startForcedCapitolDuel(EnemySprite mage) {
        EnemyData duelData = new EnemyData(mage.getData());
        duelData.gamesPerMatch = 3;
        EnemySprite duelMage = new EnemySprite(duelData);
        duelMage.territoryColor = mage.territoryColor;
        currentMob = duelMage;
        currentMobIsCapitolDefense = true;
        Forge.advFreezePlayerControls = true;
        DuelScene duelScene = DuelScene.instance();
        FThreads.invokeInEdtNowOrLater(() -> {
            Forge.setTransitionScreen(new TransitionScreen(() -> {
                Forge.advFreezePlayerControls = false;
                duelScene.initDuels(player, duelMage);
                Forge.switchScene(duelScene);
            }, ScreenUtil.getInstance().takeScreenshot(), true, false, false, false, "", Current.player().avatar(),
                    duelMage.getAtlasPath(), Current.player().getName(), duelMage.getTieredDisplayName())
                    .withEnemyStatKey(duelMage.getName()));
            WorldSave.getCurrentSave().autoSave();
        });
    }

    // Chest loot spawn - "Dangerous Enemy" event (2026-08-25 user revision: "Start the duel
    // immediately. Don't just spawn the enemy" - replaces the original opt-in
    // spawn-near-player-and-let-them-walk-into-it design). Same direct-launch template as
    // startForcedCapitolDuel above, minus the Capitol-defense-specific bookkeeping (this isn't a
    // run-ending encounter). currentMob still needs setting - WorldStage.setWinner() reads
    // currentMob.getRewards() on a win, the same reward-granting pipeline every other duel uses.
    public void startChestDuel(EnemySprite enemy) {
        currentMob = enemy;
        Forge.advFreezePlayerControls = true;
        DuelScene duelScene = DuelScene.instance();
        FThreads.invokeInEdtNowOrLater(() -> {
            Forge.setTransitionScreen(new TransitionScreen(() -> {
                Forge.advFreezePlayerControls = false;
                duelScene.initDuels(player, enemy);
                Forge.switchScene(duelScene);
            }, ScreenUtil.getInstance().takeScreenshot(), true, false, false, false, "", Current.player().avatar(),
                    enemy.getAtlasPath(), Current.player().getName(), enemy.getTieredDisplayName())
                    .withEnemyStatKey(enemy.getName()));
            WorldSave.getCurrentSave().autoSave();
        });
    }

    // Capitol defense loss (MOD_SCOPE.md #7, user request 2026-08-10): nothing like a run-ending
    // state exists elsewhere in Adventure mode - built new for this. Deliberately does NOT delete
    // the save (no permadeath mechanic exists in this codebase to hook into) - a blocking dialog,
    // then back to the main menu, same "leave with a saved preview" pattern openMenu() already
    // uses for an ordinary menu exit. If this turns out too harsh (or not harsh enough), the save
    // itself is untouched either way.
    private void triggerCapitolDefeat() {
        triggerGameLost("[RED]Your Capitol has fallen![]\nWithout it, your hold on this realm is lost.");
    }

    /** Generalized run-over dialog (2026-08-15) - the message-agnostic body of what was
     *  triggerCapitolDefeat(), split out so the new "no towns left anywhere" lose condition
     *  (TerritoryControl.onMageArrived()'s post-capture check) can end the run through the exact
     *  same mechanism with its own explanation. Public: TerritoryControl lives in another package. */
    public void triggerGameLost(String message) {
        // Every loss path shares this exit, so the Bronze Coin ransom's defeat-gold suppression is
        // cleared HERE for all of them (2026-09-02 research re-verification: round 78 cleared it on
        // the Capitol-defense path only; the "no towns left" path did not).
        Current.player().clearSuppressDefeatGoldLoss();
        Forge.advFreezePlayerControls = true;
        Dialog dialog = getDialog();
        dialog.getContentTable().clear();
        dialog.getButtonTable().clear();
        dialog.clearListeners();

        TypingLabel label = Controls.newTypingLabel(message);
        label.setWrap(true);
        label.skipToTheEnd();
        dialog.getContentTable().add(label).width(250f).row();

        dialog.getButtonTable().add(Controls.newTextButton("Return to Main Menu", () -> {
            Forge.advFreezePlayerControls = false;
            hideDialog();
            WorldSave.getCurrentSave().header.createPreview();
            Forge.switchScene(StartScene.instance());
        })).width(240f).row();
        dialog.setKeepWithinStage(true);
        showDialog();
    }

    // Severe-tier ordinary towns show a real blocking dialog (user request 2026-08-08 - the old
    // corner notification was easy to miss, so a barred town just read as "I walk right through
    // this area" with no explanation of why it never opens). Same dialog styling as the capital
    // toll below, single Leave button - there's nothing to pay here, ordinary towns bar outright.
    private void showEntryBarredDialog(PointOfInterest poi, String barredColor) {
        Dialog dialog = getDialog();
        dialog.getContentTable().clear();
        dialog.getButtonTable().clear();
        dialog.clearListeners();

        boolean canAttack = Config.instance().getConfigData().warTownAssaultEnabled
                && TerritoryControl.basicLandFor(barredColor) != null;
        TypingLabel label = Controls.newTypingLabel("The guards of " + poi.getDisplayName()
                + " bar you from entering - you are at [RED]War[] with " + capitalizeColor(barredColor) + "!"
                + (canAttack ? " Their defenders stand ready; you may leave, or attack the town." : ""));
        label.setWrap(true);
        label.skipToTheEnd();
        dialog.getContentTable().add(label).width(250f).row();

        if (canAttack) {
            dialog.getButtonTable().add(Controls.newTextButton("Attack", () -> {
                hideDialog();
                startTownAssault(poi, barredColor);
            })).width(240f).row();
        }
        dialog.getButtonTable().add(Controls.newTextButton("Leave", this::hideDialog)).width(240f).row();
        dialog.setKeepWithinStage(true);
        showDialog();
    }

    // Standalone welcome popup (MOD_SCOPE.md #89, relocated here 2026-08-20): plain OK-dialog
    // with the plane's config.json welcomePopupText, shown by GameScene.enter() once per save
    // on the first WORLD-MAP entry - the spawn-dungeon placements collided with the tutorial
    // intro dialog (see TileMapScene.initializeDialogs()).
    public void showWelcomeDialog(String text) {
        // Long-text overflow fix (2026-08-24 user report, screenshot showed the popup spilling
        // off the top of the screen with no scrollbar) - same root cause InfoTextScene's own
        // class comment documents: Dialog.show() packs the WHOLE window to its content with no
        // way to cap it below the 480x270 virtual screen every scene here is laid out on, so an
        // inner ScrollPane's height cap does nothing to stop the outer Dialog overflowing.
        // Delegates to InfoTextScene (the fixed-size, actually-scrollable page already built for
        // this exact problem - see "How Guards Work"/"Mod Details") instead of a raw Dialog.
        // Paragraph breaks match how welcomePopupText is authored in config.json (blank-line-
        // separated), same convention InfoTextScene's other callers use for a hand-split list.
        // welcomePopupLink (config.json, optional) renders as a real browser-opening button.
        InfoTextScene.show("Welcome", Arrays.asList(text.split("\n\n")),
                "Join us on Discord", Config.instance().getConfigData().welcomePopupLink);
    }

    // Side-quest timer expiry (user request 2026-08-08): a real blocking dialog, same pattern as
    // the war-entry dialog above - the old corner toast was easy to miss entirely, especially at
    // 100x fast-forward. One dialog lists every quest that failed on the same day tick.
    public void showQuestsFailedDialog(java.util.List<String> questNames) {
        Dialog dialog = getDialog();
        dialog.getContentTable().clear();
        dialog.getButtonTable().clear();
        dialog.clearListeners();

        StringBuilder text = new StringBuilder("[RED]Quest Failed![]");
        for (String questName : questNames)
            text.append("\nYou did not complete [!]").append(questName).append("[] in time.");
        TypingLabel label = Controls.newTypingLabel(text.toString());
        label.setWrap(true);
        label.skipToTheEnd();
        dialog.getContentTable().add(label).width(250f).row();

        dialog.getButtonTable().add(Controls.newTextButton("OK", this::hideDialog)).width(240f).row();
        dialog.setKeepWithinStage(true);
        showDialog();
    }

    // Severe-tier capitals charge a toll instead of barring outright (user request - capitals
    // hold story content, a hard bar risks soft-locks; and paying your way past hostile guards
    // is good flavor). Paying replicates the exact entry sequence the normal collision path
    // runs (autoSave -> loadPOI -> checkOut -> visit). Declining just closes - collidingPoint
    // is already set by the caller, so it won't re-prompt until the player walks off and back.
    private void showCapitalTollDialog(PointOfInterest poi, PointOfInterestMapSprite point) {
        Dialog dialog = getDialog();
        dialog.getContentTable().clear();
        dialog.getButtonTable().clear();
        dialog.clearListeners();

        TypingLabel label = Controls.newTypingLabel("The guards of " + poi.getDisplayName()
                + " bar your way, but greed outweighs grudges: they'll let you pass for [+Gold] "
                + ColorReputation.CAPITAL_ENTRY_TOLL + " gold.");
        label.setWrap(true);
        label.skipToTheEnd();
        dialog.getContentTable().add(label).width(250f).row();

        // NEGATIVE GOLD BUG (Android tester report 2026-08-30, reproduced by arithmetic: toll 500,
        // tester ended on -167, i.e. they paid with 333). This is the SAME latent trap the round-44
        // review found on the Upgrade-to-Exchange button: this UI framework's setDisabled() greys a
        // button but does NOT detach its click handler, so the handler below stayed live while the
        // button looked unavailable - and takeGold() does not clamp at zero (`gold -= price`), so
        // the payment simply went through into the negative.
        // Fixed the way MapDialog already does it correctly: decide affordability FIRST and only
        // attach a handler when it is actually payable, so there is no live handler to fire. The
        // in-handler re-check is belt-and-braces in case gold changes between build and click.
        boolean canAffordToll = Current.player().getGold() >= ColorReputation.CAPITAL_ENTRY_TOLL;
        TextraButton payButton = canAffordToll
                ? Controls.newTextButton("Pay " + ColorReputation.CAPITAL_ENTRY_TOLL + " gold", () -> {
                    if (Current.player().getGold() < ColorReputation.CAPITAL_ENTRY_TOLL)
                        return;
                    Current.player().takeGold(ColorReputation.CAPITAL_ENTRY_TOLL);
                    hideDialog();
                    WorldSave.getCurrentSave().autoSave();
                    loadPOI(poi);
                    point.getMapSprite().checkOut();
                    WorldSave.getCurrentSave().getPointOfInterestChanges(poi.getID()).visit();
                })
                : Controls.newTextButton("Pay " + ColorReputation.CAPITAL_ENTRY_TOLL + " gold (you have "
                    + Current.player().getGold() + ")");
        payButton.setDisabled(!canAffordToll);
        dialog.getButtonTable().add(payButton).width(240f).row();
        dialog.getButtonTable().add(Controls.newTextButton("Leave", this::hideDialog)).width(240f).row();
        dialog.setKeepWithinStage(true);
        showDialog();
    }

    private static boolean isLegendaryPoi(PointOfInterest poi) {
        if (poi == null || poi.getData() == null || poi.getData().questTags == null)
            return false;
        for (String tag : poi.getData().questTags) {
            if ("Legendary".equals(tag))
                return true;
        }
        return false;
    }

    // Legendary-dungeon entry warning (2026-08-21, v1.00 feedback "Tier 1"): plain Enter/Turn
    // Back gate, styled after showCapitalTollDialog above - Enter replicates the exact normal
    // entry sequence (autoSave -> loadPOI -> checkOut -> visit).
    private void showLegendaryWarningDialog(PointOfInterest poi, PointOfInterestMapSprite point) {
        Dialog dialog = getDialog();
        dialog.getContentTable().clear();
        dialog.getButtonTable().clear();
        dialog.clearListeners();

        TypingLabel label = Controls.newTypingLabel("[RED]" + poi.getDisplayName() + "[] radiates "
                + "power far beyond the surrounding lands. The legends within were never scaled "
                + "to this world's dangers - turn back unless you are truly ready.");
        label.setWrap(true);
        label.skipToTheEnd();
        dialog.getContentTable().add(label).width(250f).row();

        dialog.getButtonTable().add(Controls.newTextButton("Enter", () -> {
            hideDialog();
            WorldSave.getCurrentSave().autoSave();
            loadPOI(poi);
            point.getMapSprite().checkOut();
            WorldSave.getCurrentSave().getPointOfInterestChanges(poi.getID()).visit();
        })).width(240f).row();
        dialog.getButtonTable().add(Controls.newTextButton("Turn Back", this::hideDialog)).width(240f).row();
        dialog.setKeepWithinStage(true);
        showDialog();
    }

    // Chest loot spawn - "Duplicate" event (2026-08-25 user spec). Public: ChestEvents lives in
    // util, not stage, and needs WorldStage's private Dialog infra the same way every other
    // world-map popup above does. A free-form card-picker UI doesn't exist anywhere in this
    // codebase's simple Dialog+TypingLabel+buttons pattern, so - same documented simplification
    // as the Thief Merchant event below - the traveling artificer duplicates a RANDOM owned card
    // instead of letting the player choose one. cheapCard/expensiveCard are pre-picked by
    // ChestEvents (expensiveCard null when the player owns no restricted card at all, in which
    // case only the cheap button is offered).
    public void showChestDuplicateDialog(forge.item.PaperCard cheapCard, forge.item.PaperCard expensiveCard) {
        Dialog dialog = getDialog();
        dialog.getContentTable().clear();
        dialog.getButtonTable().clear();
        dialog.clearListeners();

        TypingLabel label = Controls.newTypingLabel("A traveling artificer emerges from the chest: "
                + "\"I can duplicate one of your cards, for a price.\"");
        label.setWrap(true);
        label.skipToTheEnd();
        dialog.getContentTable().add(label).width(250f).row();

        final int cheapCost = 25;
        TextraButton cheapButton = Controls.newTextButton("Duplicate " + cheapCard.getName() + " (" + cheapCost + " shards)", () -> {
            Current.player().takeShards(cheapCost);
            // Goes to the general Inventory/collection, NOT the active deck itself (2026-08-26
            // user revision: "the duplicate was returned to Inventory" / "The player might have 4
            // of the card and if we add it to his deck, it will be illegal" - a deck already at
            // its 4-copy limit for this card would become an illegal decklist. The card is still
            // CHOSEN from the active deck's contents - only where the duplicate itself lands
            // changed).
            Current.player().addCard(cheapCard, 1);
            hideDialog();
            GameHUD.getInstance().addNotification("The artificer duplicates your " + cheapCard.getName() + "!");
        });
        cheapButton.setDisabled(Current.player().getShards() < cheapCost);
        dialog.getButtonTable().add(cheapButton).width(240f).row();

        if (expensiveCard != null) {
            final int expensiveCost = 200;
            TextraButton expensiveButton = Controls.newTextButton("Duplicate " + expensiveCard.getName() + " (" + expensiveCost + " shards)", () -> {
                Current.player().takeShards(expensiveCost);
                Current.player().addCard(expensiveCard, 1);
                hideDialog();
                GameHUD.getInstance().addNotification("The artificer duplicates your " + expensiveCard.getName() + "!");
            });
            expensiveButton.setDisabled(Current.player().getShards() < expensiveCost);
            dialog.getButtonTable().add(expensiveButton).width(240f).row();
        }
        dialog.getButtonTable().add(Controls.newTextButton("Decline", this::hideDialog)).width(240f).row();
        dialog.setKeepWithinStage(true);
        showDialog();
    }

    @Override
    public boolean isColliding(Rectangle boundingRect) {
        if (currentModifications.containsKey(PlayerModification.Fly))
            return false;
        return WorldSave.getCurrentSave().getWorld().collidingTile(boundingRect);
    }

    @Override
    public Vector2 adjustMovement(Vector2 direction, Rectangle boundingRect) {
        if (isColliding(boundingRect)) //if player is already colliding (after flying or teleport) allow to move off collision
            return direction;
        return super.adjustMovement(direction, boundingRect);
    }

    public boolean spawn(String enemy) {
        return spawn(WorldData.getEnemy(enemy));
    }

    // Territory Effects (MOD_SCOPE.md #17, user spec 2026-08-14 - the first concrete numbers ever
    // given for this item; before this round it was only a "candidate effect, none committed" in
    // the doc, confirmed via a direct code search that nothing implemented it yet). Multiplicative
    // with the existing road/sprint modifiers in handleMonsterSpawn() below - a road through
    // hostile land still gets its own flat 1.5x, unaffected (this only applies to the off-road
    // case, the only one with a well-defined "whose biome is this" lookup already on hand without
    // unmasking the road bit from a road tile's own highestBiome() result). All 5 percentages are
    // user-tunable via tuning.json (TuningData.java) rather than hardcoded here.
    // Diagnostic logging state (standing practice, CLAUDE.md) - this modifier recomputes every
    // frame from handleMonsterSpawn(), so logging every call would flood forge.log; only log when
    // the biome name OR the computed modifier actually changed (a reputation-tier crossing can
    // change the modifier while the player stays on one continuously-named biome, e.g. standing
    // still during a fight that shifts that color's reputation - biome-name-only tracking missed
    // that case, adversarial review finding 2026-08-15). Reset in clearCache() (called by load())
    // so a stale value from a previous session's last-logged biome can't suppress the first real
    // entry after loading a save; a brand new game doesn't route through clearCache() at all, so
    // this reset doesn't cover that path - narrow, cosmetic-only residual gap, not worth new
    // new-game-init plumbing to close.
    private String lastLoggedSpeedBiome = null;
    private float lastLoggedSpeedModifier = Float.NaN;

    // Floor (2026-08-15 adversarial review finding): tuning.json's penalty percentages are
    // user-editable with no validation anywhere in the load path. An edited value >= 1.0 would
    // drive this to zero or negative, and libGDX's Vector2.setLength() squares its argument
    // (discarding sign) while also being unable to un-zero an already-zeroed vector via a later
    // call - either outcome silently breaks player movement rather than just being "extra harsh."
    // Ships safe today (max shipped penalty is 0.10), but the accessor itself should never be able
    // to produce an unusable result regardless of what a modder puts in the config.
    private static final float MIN_TERRITORY_SPEED_MODIFIER = 0.1f;

    private float territorySpeedModifier(BiomeData data) {
        if (data == null || data.name == null)
            return 1f;
        TuningData tuning = Config.instance().getTuningData();
        float modifier;
        if ("player".equals(data.name)) {
            modifier = 1f + tuning.playerTerritorySpeedBonus;
        } else if (!ColorReputation.isEnabled()) {
            modifier = 1f;
        } else {
            boolean isAiColor = false;
            for (String c : ColorReputation.COLORS)
                if (c.equals(data.name)) { isAiColor = true; break; }
            if (!isAiColor) {
                modifier = 1f; // colorless/wasteland/ocean/base - no effect
            } else {
                switch (ColorReputation.getStatus(data.name)) {
                    case PARTNER: modifier = 1f + tuning.aiTerritoryPartnerSpeedBonus; break;
                    case HAPPY: modifier = 1f + tuning.aiTerritoryHappySpeedBonus; break;
                    case UNHAPPY: modifier = 1f - tuning.aiTerritoryUnhappySpeedPenalty; break;
                    case WAR: modifier = 1f - tuning.aiTerritoryWarSpeedPenalty; break;
                    default: modifier = 1f; break; // Neutral
                }
            }
        }
        modifier = Math.max(MIN_TERRITORY_SPEED_MODIFIER, modifier);
        if (!data.name.equals(lastLoggedSpeedBiome) || modifier != lastLoggedSpeedModifier) {
            lastLoggedSpeedBiome = data.name;
            lastLoggedSpeedModifier = modifier;
            System.out.println("[TFR-TerritorySpeed] entered '" + data.name + "' terrain, move modifier now " + modifier + "x");
        }
        return modifier;
    }

    private void handleMonsterSpawn(float delta) {
        for (EnemySprite questSprite : AdventureQuestController.instance().getQuestSprites()) {
            if (!foregroundSprites.getChildren().contains(questSprite, true)) {
                spawnQuestSprite(questSprite,2.5f);
            }
        }

        World world = WorldSave.getCurrentSave().getWorld();
        int currentBiome = World.highestBiome(world.getBiome((int) ((player.getX() + player.getWidth() / 2f) / world.getTileSize()), (int) (player.getY() / world.getTileSize())));
        List<BiomeData> biomeData = WorldSave.getCurrentSave().getWorld().getData().GetBiomes();
        float sprintingMod = currentModifications.containsKey(PlayerModification.Sprint) ? 2 : 1;
        float runMod = isRunKeyHeld() ? RUN_KEY_SPEED_MULTIPLIER : 1f;
        if (biomeData.size() <= currentBiome) {// "if isOnRoad
            player.setMoveModifier(1.5f * sprintingMod * runMod);
            return;
        }
        BiomeData data = biomeData.get(currentBiome);
        if (data == null) return;
        player.setMoveModifier(1.0f * sprintingMod * territorySpeedModifier(data) * runMod);

        spawnDelay -= delta;
        if (spawnDelay >= 0) return;
        spawnDelay = spawnInterval + (rand.nextFloat() * 4.0f);

        // Roaming-spawn intrusion (MOD_SCOPE.md #7 follow-up, user request 2026-08-10): a nearby
        // foreign-color town/capital/castle can bleed its color's monsters into this spawn roll,
        // scaled by reputation with that color (War-tier borders are actively hostile; Partner-tier
        // ones never intrude). Only substitutes THIS roll's biome, not the player's actual location
        // - a cheap per-roll override rather than a real territory change.
        //
        // Moved BELOW the spawnDelay gate (2026-08-13 log review): this block previously ran on
        // EVERY frame, not once per spawn attempt - at ~60fps in War territory that was ~37
        // [TFR-Intrusion] log lines per second (89.5% of an entire play session's forge.log, 57.6k
        // lines), plus a wasted RNG draw and territory scan per frame. Only the roll landing on
        // the frame the delay expired ever affected a spawn; the per-spawn substitution
        // probability is unchanged by evaluating it once, here, after the gate.
        if (ColorReputation.isEnabled()) {
            String foreignColor = TerritoryControl.findNearbyForeignColor(world, player.pos(), data.name);
            if (foreignColor != null) {
                float chance = SPAWN_INTRUSION_BASE_CHANCE * ColorReputation.getSpawnIntrusionMultiplier(foreignColor);
                if (chance > 0f && rand.nextFloat() < chance) {
                    BiomeData intruding = findBiomeByName(biomeData, foreignColor);
                    if (intruding != null) {
                        // Diagnostic-only logging (user request 2026-08-10, "hard to test in-game")
                        // - greppable in forge.log as "[TFR-Intrusion]". See MOD_CHANGELOG.md's
                        // "Playtest logging" entry for the full checklist this feeds.
                        System.out.println("[TFR-Intrusion] " + data.name + " territory -> " + foreignColor
                                + " intrusion fired (chance=" + chance + ", status=" + ColorReputation.getStatus(foreignColor) + ")");
                        data = intruding;
                    }
                }
            }
        }

        // Colorless/Wasteland mix-in (user request 2026-08-14, see PLAYER_COLORLESS_MIX_CHANCE's
        // own comment) - only when this roll is still genuinely on the player's own biome (an
        // intrusion substitution above already picked a different roster for this roll, and
        // shouldn't be double-overridden by an unrelated mechanic).
        if ("player".equals(data.name) && rand.nextFloat() < PLAYER_COLORLESS_MIX_CHANCE) {
            BiomeData colorless = findBiomeByName(biomeData, "colorless");
            if (colorless != null) {
                System.out.println("[TFR-ColorlessMix] player territory -> colorless mix-in fired (chance=" + PLAYER_COLORLESS_MIX_CHANCE + ")");
                data = colorless;
            }
        }

        ArrayList<EnemyData> list = data.getEnemyList();
        if (list == null)
            return;
        // BiomeData.getEnemy() used to silently discard whatever was passed here and substitute
        // player rank itself (a real bug, fixed 2026-08-10) - now the caller's job. Player rank
        // is still the base signal (unchanged progression feel), the intrusion substitution above
        // is a separate, independent axis (which biome's list to draw from, not how hard within it).
        float difficultyFactor = Current.player().getStatistic().rank();

        // Very-rare War-tier boss encounter (user request 2026-08-10): only once the effective
        // color for THIS roll (post-intrusion above) is one the player is genuinely At War with -
        // "in those colored areas, when the player is at war with that color." Falls through to
        // the ordinary pick below on a miss, same as any other roll.
        EnemyData enemyData = null;
        if (ColorReputation.isEnabled() && ColorReputation.getStatus(data.name) == ColorReputation.Status.WAR) {
            enemyData = TerritoryControl.rollWarTierBoss(data.name, rand);
            if (enemyData != null) {
                // Diagnostic-only (user request 2026-08-10) - this fires ~4% of eligible rolls by
                // design, so seeing it at all during a session confirms the mechanic works.
                System.out.println("[TFR-WarBoss] " + enemyData.getName() + " spawned in " + data.name + " territory (War-tier)");
            }
        }
        if (enemyData == null) {
            enemyData = pickNonClusteringEnemy(data, difficultyFactor);
            if (enemyData != null) {
                // Diagnostic-only (user request 2026-08-10) - the bulk of the log; see
                // MOD_CHANGELOG.md's "Playtest logging" entry for how to summarize this instead of
                // reading it line by line (tier distribution, color variety, confirming the 11
                // colors:"C" enemies fixed this round actually appear, etc.). speed/life added
                // 2026-08-13 (diagnostic logging standard) - the raw catalog values for a roaming
                // enemy at the moment it spawns, otherwise not printed anywhere.
                String spawnTierInfo = "";
                if (SpawnTierWeighting.isEnabled()) {
                    int week = SpawnTierWeighting.currentWeek(world);
                    spawnTierInfo = ", week=" + week + ", permanentKills=" + SpawnTierWeighting.getPermanentKillCount(enemyData.getName());
                }
                // shown= is the tiered display name the player actually sees on screen (2026-08-27:
                // a "day-1 Master" report was un-greppable because this line only had the raw name).
                String shown = enemyData.getTieredDisplayName();
                String shownInfo = shown.equals(enemyData.getName()) ? "" : ", shown=\"" + shown + "\"";
                System.out.println("[TFR-Spawn] " + enemyData.getName() + " (tier=" + enemyData.tier
                        + shownInfo
                        + ", colors=" + enemyData.colors + ", speed=" + enemyData.speed
                        + ", life=" + enemyData.life + ") in " + data.name + " territory (rank=" + difficultyFactor
                        + spawnTierInfo + ")");
            }
        }
        EnemyData extraSpawnForQuests = data.getExtraSpawnEnemy(difficultyFactor);
        if (extraSpawnForQuests != null) {
            // This path (quest-tag extra spawns) bypasses the weighted tier system by design -
            // quest-authored enemies spawn as authored. Logged since 2026-08-27 (it was the one
            // completely silent world-map spawn path) so tier reports stay attributable.
            System.out.println("[TFR-Spawn] quest-extra " + extraSpawnForQuests.getName()
                    + " (tier=" + extraSpawnForQuests.tier + ", shown=\""
                    + extraSpawnForQuests.getTieredDisplayName() + "\") in " + data.name + " territory");
            float spawnPicker = rand.nextFloat();

            if (spawnPicker > 0.5f) //todo: make this difficulty dependent, more enemies on harder difficulty
            {
                spawn(enemyData);
                spawn(extraSpawnForQuests);
            }
            else if (spawnPicker > 0.2f) {
                spawn(extraSpawnForQuests);
            }
            else {
                spawn(enemyData);
            }

        }
        else spawn(enemyData);
    }

    /**
     * The ordinary weighted biome pick, re-rolled while it keeps landing on an enemy the player
     * already has too many of standing next to them.
     * <p>
     * User report 2026-09-01: "I found 3 instances where there were multiples of the exact same
     * enemy. There was 3 Khenra Warriors close to each other" - confirmed in forge.log, which
     * shows three consecutive {@code [TFR-Spawn] Khenra Warrior} lines in the same week and
     * territory. Nothing was broken: {@code BiomeData.getEnemy()} is a memoryless weighted draw
     * over the biome's list, so a common entry naturally comes up several rolls running, and no
     * code had ever looked at what was already on screen.
     * <p>
     * Deliberately a RE-ROLL, never a skipped spawn. Refusing to spawn would silently thin the
     * world wherever a biome list is short, and would be invisible in play - a rarer bug than the
     * one being fixed but a worse one. After {@code sameEnemySpawnRerolls} attempts the duplicate
     * is spawned regardless, so a one-entry biome list still populates normally.
     * <p>
     * Only the ORDINARY pick routes through here. War-tier bosses (already ~4% of eligible rolls)
     * and quest-tag extra spawns are authored encounters and keep spawning as authored.
     *
     * @return the chosen enemy, or null if the biome list itself yields nothing
     */
    private EnemyData pickNonClusteringEnemy(BiomeData data, float difficultyFactor) {
        EnemyData pick = data.getEnemy(difficultyFactor);
        ConfigData config = Config.instance().getConfigData();
        if (pick == null || config == null || !config.spawnDuplicateLimitEnabled)
            return pick;
        TuningData tuning = Config.instance().getTuningData();
        int limit = tuning == null ? 2 : tuning.maxSameEnemyNearby;
        int rerolls = tuning == null ? 4 : tuning.sameEnemySpawnRerolls;
        float radius = tuning == null ? 220f : tuning.sameEnemyNearbyRadius;
        if (limit <= 0 || rerolls <= 0)
            return pick;
        for (int attempt = 0; attempt < rerolls; attempt++) {
            int nearby = countSameEnemyNearby(pick.getName(), radius);
            if (nearby < limit)
                return pick;
            EnemyData retry = data.getEnemy(difficultyFactor);
            // A null retry means the list stopped yielding - keep what we already had rather than
            // losing the spawn entirely.
            if (retry == null)
                break;
            System.out.println("[TFR-SpawnDedupe] " + pick.getName() + " already has " + nearby
                    + " within " + radius + " units (limit " + limit + ") - re-rolled to "
                    + retry.getName() + " (attempt " + (attempt + 1) + "/" + rerolls + ")");
            pick = retry;
        }
        int finalNearby = countSameEnemyNearby(pick.getName(), radius);
        if (finalNearby >= limit)
            System.out.println("[TFR-SpawnDedupe] gave up after " + rerolls + " re-rolls - spawning "
                    + pick.getName() + " anyway with " + finalNearby + " already nearby (biome '"
                    + data.name + "' may not have enough distinct entries at this difficulty)");
        return pick;
    }

    /** Live roaming enemies of this exact name within {@code radius} world units of the player.
     *  Keyed on the RAW catalog name, not the tiered display name - two different tiers of the
     *  same creature are what the player sees as "the same enemy". */
    private int countSameEnemyNearby(String enemyName, float radius) {
        if (enemyName == null)
            return 0;
        float radiusSq = radius * radius;
        float px = player.getX();
        float py = player.getY();
        int count = 0;
        for (Pair<Float, EnemySprite> entry : enemies) {
            EnemySprite other = entry.getValue();
            if (other == null || other.getData() == null || !enemyName.equals(other.getData().getName()))
                continue;
            float dx = other.getX() - px;
            float dy = other.getY() - py;
            if (dx * dx + dy * dy <= radiusSq)
                count++;
        }
        return count;
    }

    private static BiomeData findBiomeByName(List<BiomeData> biomes, String name) {
        for (BiomeData b : biomes) {
            if (name.equals(b.name))
                return b;
        }
        return null;
    }

    private boolean spawn(EnemySprite sprite){
        if (sprite == null)
            return false;
        float unit = Scene.getIntendedHeight() / 6f;
        Vector2 spawnPos = new Vector2(1, 1);
        for (int j = 0; j < 10; j++) {
            spawnPos.setLength(unit + (unit * 3) * rand.nextFloat());
            spawnPos.setAngleDeg(360 * rand.nextFloat());
            for (int i = 0; i < 10; i++) {
                boolean enemyXIsBigger = sprite.getX() > player.getX();
                boolean enemyYIsBigger = sprite.getY() > player.getY();
                sprite.setX(player.getX() + spawnPos.x + (i * sprite.getWidth() * (enemyXIsBigger ? 1 : -1)));//maybe find a better way to get spawn points
                sprite.setY(player.getY() + spawnPos.y + (i * sprite.getHeight() * (enemyYIsBigger ? 1 : -1)));
                if (sprite.getData().flying || !WorldSave.getCurrentSave().getWorld().collidingTile(sprite.boundingRect())) {
                    enemies.add(Pair.of(globalTimer, sprite));
                    foregroundSprites.addActor(sprite);
                    return true;
                }
            }
        }
        return false;
    }

    // Territory Control (MOD_SCOPE.md #7): places a mage directly at a given world position
    // (a castle) rather than scattered near the player like every other spawn(...) overload here -
    // for TerritoryControl (a different package) to call. No collision retry loop since castles
    // sit in open territory; the mage's own movement (onActing's homing block) handles obstacles
    // once it's underway.
    public void spawnAt(EnemySprite sprite, Vector2 pos) {
        sprite.setX(pos.x);
        sprite.setY(pos.y);
        enemies.add(Pair.of(globalTimer, sprite));
        foregroundSprites.addActor(sprite);
    }

    private boolean spawn(EnemyData enemyData) {
        if (enemyData == null)
            return false;
        EnemySprite sprite = new EnemySprite(enemyData);
        return spawn(sprite);

    }

    private boolean spawnQuestSprite(EnemySprite sprite, float distanceMultiplier){
        if (sprite == null)
            return false;
        float unit = Scene.getIntendedHeight() / 6f;
        Vector2 spawnPos = new Vector2(1, 1);
        for (int j = 0; j < 10; j++) {
            spawnPos.setLength((unit + (unit * 3) * rand.nextFloat()) * distanceMultiplier);
            spawnPos.setAngleDeg(360 * rand.nextFloat());
            for (int i = 0; i < 10; i++) {
                boolean enemyXIsBigger = sprite.getX() > player.getX();
                boolean enemyYIsBigger = sprite.getY() > player.getY();
                sprite.setX(player.getX() + spawnPos.x + (i * sprite.getWidth() * (enemyXIsBigger ? 1 : -1)));//maybe find a better way to get spawn points
                sprite.setY(player.getY() + spawnPos.y + (i * sprite.getHeight() * (enemyYIsBigger ? 1 : -1)));
                if (sprite.getData().flying || !WorldSave.getCurrentSave().getWorld().collidingTile(sprite.boundingRect())) {
                    enemies.add(Pair.of(globalTimer, sprite));
                    foregroundSprites.addActor(sprite);
                    // Was the last completely silent world-map spawn source (2026-08-27) -
                    // Hunt-quest targets land here via AdventureQuestController.getQuestSprites().
                    System.out.println("[TFR-Spawn] quest-sprite " + sprite.getData().getName()
                            + " (tier=" + sprite.getData().tier + ", shown=\""
                            + sprite.getData().getTieredDisplayName() + "\")");
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public void draw() {
        background.setPlayerPos(player.getX(), player.getY());
        //spriteGroup.setCullingArea(new Rectangle(player.getX()-getViewport().getWorldHeight()/2,player.getY()-getViewport().getWorldHeight()/2,getViewport().getWorldHeight(),getViewport().getWorldHeight()));
        super.draw();
    }

    public void enterSpawnPOI(){
        enterSpawnPOI = true; //On a new game, we want to automatically enter spawn POI the player overlaps with.
    }

    public PointOfInterestMapSprite getMapSprite(PointOfInterest poi) {
        if (poi == null)
            return null;
        for (Actor actor : foregroundSprites.getChildren()) {
            if (actor.getClass() == PointOfInterestMapSprite.class) {
                PointOfInterestMapSprite point = (PointOfInterestMapSprite) actor;
                if (poi == point.getPointOfInterest() && poi.getPosition() == point.getPointOfInterest().getPosition())
                    return point;
            }
        }
        return null;
    }

    @Override
    public void enter() {
        getPlayerSprite().LoadPos();
        getPlayerSprite().setMovementDirection(Vector2.Zero);
        if (enterSpawnPOI) {
            enterSpawnPOI = false;
            PointOfInterest poi = Current.world().findPointsOfInterest("Spawn");
            if (poi != null) { //shouldn't be null
                WorldStage.getInstance().loadPOI(poi);
                // adjust player sprite to prevent triggering the poi collision point when leaving the spawn on New Game
                WorldStage.getInstance().getPlayerSprite().storePos(poi.getPosition().x, poi.getPosition().y + 18f);
            }
        }
        else {
            for (Actor actor : foregroundSprites.getChildren()) {
                if (actor.getClass() == PointOfInterestMapSprite.class) {
                    PointOfInterestMapSprite point = (PointOfInterestMapSprite) actor;
                    if (player.collideWith(point.getBoundingRect())) {
                        collidingPoint = point;
                    }
                }
            }
        }
        setBounds(WorldSave.getCurrentSave().getWorld().getWidthInPixels(), WorldSave.getCurrentSave().getWorld().getHeightInPixels());
        GridPoint2 pos = background.translateFromWorldToChunk(player.getX(), player.getY());
        background.loadChunk(pos.x, pos.y);
        super.enter();
    }

    @Override
    public void leave() {
        getPlayerSprite().storePos();
    }

    @Override
    public void load(SaveFileData data) {
        try {
            clearCache();
            MapStage.getInstance().clearIsInMap();
            GameHUD.getInstance().clearNotifications();
            List<Float> timeouts = (List<Float>) data.readObject("timeouts");
            List<String> names = (List<String>) data.readObject("names");
            List<Float> x = (List<Float>) data.readObject("x");
            List<Float> y = (List<Float>) data.readObject("y");
            List<String> questStageIDs = (List<String>) data.readObject("questStageIDs");
            // Both absent on a save predating mage persistence (see save() below) - such a save's
            // mages simply load the old way, as plain roaming monsters.
            List<String> territoryColors = data.containsKey("territoryColors") ? (List<String>) data.readObject("territoryColors") : null;
            List<String> territoryTargetIds = data.containsKey("territoryTargetIds") ? (List<String>) data.readObject("territoryTargetIds") : null;
            // Absent on a save predating the mage-persists-on-loss change - defaults to "never
            // engaged" (-1), same as a freshly-dispatched mage.
            List<Integer> lastDuelDays = data.containsKey("lastDuelDays") ? (List<Integer>) data.readObject("lastDuelDays") : null;
            for (int i = 0; i < timeouts.size(); i++) {
                // Null-guard (2026-08-13, Challenger-rename companion): an unresolvable saved name
                // previously hit `new EnemySprite(null)` -> NPE swallowed by this method's empty
                // catch, silently dropping EVERY remaining roaming enemy plus the globalTimer read.
                // Reachable via stale saves after a nameOverride change (e.g. pre-rename saves
                // stored "Challenger", which only resolved through the override fallback).
                EnemyData resolved = WorldData.getEnemy(names.get(i));
                if (resolved == null) {
                    System.err.println("[TFR-RoamLoad] dropping unresolvable roaming enemy \"" + names.get(i)
                            + "\" from save (renamed or removed from enemies.json?)");
                    continue;
                }
                // Territory mages keep their dispatch-time sprite-scale normalization across a
                // save/load (2026-08-26, companion to TerritoryControl.dispatch()'s own clone) -
                // this rebuild reads the SHARED template, which still carries an oversized
                // "Legends" scale, so without re-normalizing here a saved 1x mage came back 2x.
                boolean isTerritoryMage = territoryTargetIds != null && i < territoryTargetIds.size()
                        && territoryTargetIds.get(i) != null;
                if (isTerritoryMage && resolved.scale != 1.0f) {
                    resolved = new EnemyData(resolved);
                    resolved.scale = 1.0f;
                }
                EnemySprite sprite = new EnemySprite(resolved);
                sprite.setX(x.get(i));
                sprite.setY(y.get(i));
                sprite.questStageID = questStageIDs.get(i);
                if (sprite.questStageID != null)
                    AdventureQuestController.instance().rematchQuestSprite(sprite);
                if (lastDuelDays != null && i < lastDuelDays.size() && lastDuelDays.get(i) != null)
                    sprite.lastDuelDay = lastDuelDays.get(i);
                if (territoryTargetIds != null && i < territoryTargetIds.size() && territoryTargetIds.get(i) != null) {
                    // WorldSave.load() loads World (and its POIs) before this method runs, so the
                    // id resolves against the same world state the save captured. If it somehow
                    // doesn't resolve, the mage degrades to a plain roaming monster (the same
                    // no-op-on-stale-state stance TerritoryControl.onMageArrived() already takes)
                    // rather than failing the whole load.
                    String targetId = territoryTargetIds.get(i);
                    for (PointOfInterest poi : WorldSave.getCurrentSave().getWorld().getAllPointOfInterest()) {
                        if (targetId.equals(poi.getID())) {
                            sprite.territoryTarget = poi;
                            sprite.territoryColor = territoryColors != null && i < territoryColors.size() ? territoryColors.get(i) : null;
                            break;
                        }
                    }
                }
                enemies.add(Pair.of(timeouts.get(i), sprite));
                foregroundSprites.addActor(sprite);
            }
            globalTimer = data.readFloat("globalTimer");
        } catch (Exception e) {

        }
    }

    public void clearCache() {
        for (Pair<Float, EnemySprite> enemy : enemies)
            foregroundSprites.removeActor(enemy.getValue());
        enemies.clear();
        // Resource spawn actors are also stale now - drop them and let the next tick rebuild from
        // whatever spawn list the incoming world state carries.
        for (Actor actor : resourceSpawnActors)
            foregroundSprites.removeActor(actor);
        resourceSpawnActors.clear();
        ResourceSpawns.forceResync();
        // Session-static state in the mod's world-level helpers (2026-09-02 review): neither is
        // persisted, both must forget the previous run/save here.
        DungeonRotation.resetSessionState();
        TerritoryControl.clearPendingCapitolDefense();
        background.clear();
        player = null;
        // A loaded save's first tile should always get its own log line, not get silently
        // suppressed by matching whatever biome name/modifier happened to be logged last in a
        // previous session on this same long-lived singleton (see territorySpeedModifier()'s own
        // comment).
        lastLoggedSpeedBiome = null;
        lastLoggedSpeedModifier = Float.NaN;
    }

    @Override
    public SaveFileData save() {
        SaveFileData data = new SaveFileData();
        List<Float> timeouts = new ArrayList<>();
        List<String> names = new ArrayList<>();
        List<Float> x = new ArrayList<>();
        List<Float> y = new ArrayList<>();
        List<String> questStageIDs = new ArrayList<>();
        // Territory Control (MOD_SCOPE.md #7): a mage's target/color must survive a save/load, or
        // a mid-flight mage comes back as an ordinary roaming monster - it stops seeking its town
        // (the seek branch in onActing() requires territoryTarget != null), starts homing on the
        // player instead, and loses its despawn-timer exemption (its spawn-time timeout plus
        // getLifetime()'s 20s floor has usually already elapsed by load, so it vanishes almost
        // immediately) - the announced attack silently never resolves. The target is stored by its
        // POI id (PointOfInterest.getID(), stable across save/load - derived from position+name+map,
        // which only change via transformInto(), and a capture removes the mage before that) and
        // re-resolved against the freshly-loaded world in load() below.
        List<String> territoryColors = new ArrayList<>();
        List<String> territoryTargetIds = new ArrayList<>();
        // Per-mage cooldown (MOD_SCOPE.md #7 mage-persistence change) - see EnemySprite.lastDuelDay.
        List<Integer> lastDuelDays = new ArrayList<>();
        for (Pair<Float, EnemySprite> enemy : enemies) {
            timeouts.add(enemy.getKey());
            // Raw name field, NOT getName() (2026-08-13 holistic review, pre-existing bug): the 3
            // Arena champions share nameOverride "Challenger", and getName() prefers the override -
            // so all three saved as "Challenger" and load()'s WorldData.getEnemy() lookup (raw-name
            // pass first, nameOverride fallback second) resolved every one of them back to the
            // FIRST match, silently swapping a roaming Challenger 21/22 into Challenger 20 with its
            // deck/rewards. The raw name round-trips exactly; old saves that already stored an
            // override string still resolve through the same fallback they always used.
            String rawName = enemy.getValue().getData().name;
            names.add(rawName != null && !rawName.isEmpty() ? rawName : enemy.getValue().getData().getName());
            x.add(enemy.getValue().getX());
            y.add(enemy.getValue().getY());
            questStageIDs.add(enemy.getValue().questStageID);
            territoryColors.add(enemy.getValue().territoryColor);
            territoryTargetIds.add(enemy.getValue().territoryTarget == null ? null : enemy.getValue().territoryTarget.getID());
            lastDuelDays.add(enemy.getValue().lastDuelDay);
        }
        data.storeObject("timeouts", timeouts);
        data.storeObject("names", names);
        data.storeObject("x", x);
        data.storeObject("y", y);
        data.storeObject("questStageIDs", questStageIDs);
        data.storeObject("territoryColors", territoryColors);
        data.storeObject("lastDuelDays", lastDuelDays);
        data.storeObject("territoryTargetIds", territoryTargetIds);
        data.store("globalTimer", globalTimer);
        return data;
    }

    @Override
    public Viewport getViewport() {
        return super.getViewport();
    }


    public void removeNearestEnemy() {
        float shortestDist = Float.MAX_VALUE;
        EnemySprite enemy = null;
        for (Pair<Float, EnemySprite> pair : enemies) {
            float dist = pair.getValue().pos().sub(player.pos()).len();
            if (dist < shortestDist) {
                shortestDist = dist;
                enemy = pair.getValue();
            }
        }
        if (enemy != null) {
            enemy.playEffect(Paths.EFFECT_KILL);
            removeEnemy(enemy);
            player.playEffect(Paths.TRIGGER_KILL);
        }
    }

    private void drawNavigationArrow(){
        Vector2 navDirection = null;
        for (AdventureQuestData adq: Current.player().getQuests())
        {
            if (adq.isTracked) {
                PointOfInterest nearestValidPOI = adq.getClosestValidPOI(player.getCenter());
                if (nearestValidPOI != null) {
                    navDirection = new Vector2(nearestValidPOI.getCenter()).sub(player.getCenter());
                    break;
                }

                if(adq.getTargetEnemySprite() == null
                        && adq.getActiveStages().size() > 0
                        && adq.qualifiesForDetachedQuest(adq.getActiveStages().get(0))) {
                    AdventureQuestStage brokenStage = adq.getActiveStages().get(0);
                    adq.fixOrphanedHuntQuest(brokenStage);
                    AdventureQuestController.instance().addQuestSprites(brokenStage);
                    // When we first load, we will not do this in time to actually spawn the sprite
                    // until the next loop, but as soon as the player moves, if the On the Hunt quest
                    // is tracked, we will immediately point to that sprite
                }

                if (adq.getTargetEnemySprite() != null) {
                    EnemySprite target = adq.getTargetEnemySprite();
                    for (Pair<Float, EnemySprite> active :enemies)
                    {
                        EnemySprite sprite = active.getValue();
                        if (sprite.equals(target)){
                            navDirection = new Vector2(adq.getTargetEnemySprite().getCenter()).sub(player.getCenter());
                        }
                    }
                }
                break;
            }
        }
        if (navDirection != null)
        {
            navArrow.navTargetAngle = navDirection.angleDeg();
            navArrow.setVisible(true);
            navArrow.setPosition(getPlayerSprite().getX() + (getPlayerSprite().getWidth()/2), getPlayerSprite().getY() + (getPlayerSprite().getHeight()/2));
        }
        else
        {
            navArrow.setVisible(false);
        }
    }
}
