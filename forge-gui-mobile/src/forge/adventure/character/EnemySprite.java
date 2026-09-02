package forge.adventure.character;

import com.badlogic.gdx.ai.steer.Steerable;
import com.badlogic.gdx.ai.steer.SteeringAcceleration;
import com.badlogic.gdx.ai.steer.SteeringBehavior;
import com.badlogic.gdx.ai.steer.behaviors.*;
import com.badlogic.gdx.ai.steer.utils.paths.LinePath;
import com.badlogic.gdx.ai.utils.Location;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.utils.Array;
import forge.Forge;
import forge.StaticData;
import forge.adventure.data.ConfigData;
import forge.adventure.data.DialogData;
import forge.adventure.data.EffectData;
import forge.adventure.data.EnemyData;
import forge.adventure.data.RewardData;
import forge.adventure.player.AdventurePlayer;
import forge.adventure.pointofintrest.PointOfInterest;
import forge.adventure.util.Config;
import forge.adventure.stage.MapStage;
import forge.adventure.util.ColorReputation;
import forge.adventure.util.Current;
import forge.adventure.util.EditionProgression;
import forge.adventure.world.World;
import forge.adventure.util.MapDialog;
import forge.adventure.util.Reward;
import forge.adventure.util.pathfinding.MovementBehavior;
import forge.adventure.util.pathfinding.NavigationVertex;
import forge.adventure.util.pathfinding.ProgressableGraphPath;
import forge.card.CardRarity;
import forge.card.CardRulesPredicates;
import forge.deck.CardPool;
import forge.deck.Deck;
import forge.item.PaperCard;
import forge.item.PaperCardPredicates;
import forge.util.Aggregates;
import forge.util.MyRandom;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * EnemySprite
 * Character sprite that represents an Enemy
 */
public class EnemySprite extends CharacterSprite implements Steerable<Vector2> {

    private static final SteeringAcceleration<Vector2> steerOutput =
            new SteeringAcceleration<Vector2>(new Vector2());

    Vector2 position;
    float orientation;
    Vector2 linearVelocity = new Vector2(1, 0);
    float angularVelocity;
    float maxSpeed;
    boolean independentFacing;
    SteeringBehavior<Vector2> behavior;
    boolean tagged;

    EnemyData data;
    public MapDialog dialog; //Dialog to show on contact. Overrides standard battle (can be started as an action)
    public MapDialog defeatDialog; //Dialog to show on defeat. Overrides standard death (can be removed as an action)
    public EffectData effect; //Battle effect for this enemy. Similar to a player's blessing.
    public String nameOverride = ""; //Override name of this enemy in battles.
    public String bossInsult = ""; //Override the generated insult text when you are defeated.
    public RewardData[] rewards; //Additional rewards for this enemy.
    public DialogData.ConditionData spawnCondition; //Condition to spawn.
    public LinkedList<MovementBehavior> movementBehaviors = new LinkedList<>();

    public Vector2 targetVector;
    private final Vector2 _previousPosition = new Vector2();
    private final Vector2 _previousPosition2 = new Vector2();
    private final Vector2 _previousPosition3 = new Vector2();
    private final Vector2 _previousPosition4 = new Vector2();
    private final Vector2 _previousPosition5 = new Vector2();
    private final Vector2 _previousPosition6 = new Vector2();
    private final Float _movementTimeout = 150.0f;
    private boolean _freeze = false; //freeze movement after defeating player
    public float unfreezeRange = 30.0f;
    public float threatRange = 0.0f; //If range < threatRange, begin pursuit
    public float pursueRange = 0.0f; //If range > pursueRange, abandon pursuit
    public float fleeRange = 0.0f; //If range < fleeRange, attempt to move away to fleeRange
    public float speedModifier = 0.0f; // Increase or decrease default speed
    public boolean aggro = false;
    public boolean ignoreDungeonEffect = false;
    public String questStageID;
    private ProgressableGraphPath<NavigationVertex> navPath;
    public Vector2 fleeTarget;

    // Territory Control (MOD_SCOPE.md #7): set only on a "mage" sent by a colored castle to
    // capture a nearby neutral town - null for every ordinary enemy. When set, WorldStage.onActing
    // seeks this town instead of the player; reaching it (or being defeated first) is handled by
    // TerritoryControl, not by anything in this class. territoryColor (lowercase, e.g. "green")
    // records which color dispatched this mage - tracked explicitly rather than parsed back out
    // of the enemy's display name, so it can't break if that name ever changes.
    public PointOfInterest territoryTarget;
    public String territoryColor;
    // Territory Control (MOD_SCOPE.md #7): the in-game day (World.getCurrentDay()) this mage was
    // last fought and LOST to. A losing fight no longer removes an attack mage (it survives and
    // keeps traveling) - this just blocks re-engaging the same mage again the same day, checked in
    // WorldStage.onActing()'s collision loop. -1 = never engaged. Irrelevant for ordinary enemies.
    public int lastDuelDay = -1;

    public EnemySprite(EnemyData enemyData) {
        this(0,enemyData);
    }

    public EnemySprite(int id, EnemyData enemyData) {
        super(id,enemyData.sprite);
        data = enemyData;
        float scale = data.scale;
        if (scale < 0)
            scale = 1f;
        setWidth(getWidth() * scale);
        setHeight(getHeight() * scale);
        updateBoundingRect();
        initializeBaseMovementBehavior();
    }

    public void parseWaypoints(String waypoints){
        String[] wp = waypoints.replaceAll("\\s", "").split(",");
        for (String s : wp) {
            movementBehaviors.addLast(new MovementBehavior());
            if (!movementBehaviors.isEmpty()) {
                if (s.startsWith("wait")) {
                    movementBehaviors.peekLast().duration = Float.parseFloat(s.substring(4));
                } else {
                    movementBehaviors.peekLast().destination = s;
                }
            }
        }
    }

    @Override
    void updateBoundingRect() { //We want enemies to take the full tile.
        float scale = data == null ? 1f : data.scale;
        if (scale < 0)
            scale = 1f;
        boundingRect.set(getX(), getY(), getWidth(), getHeight());
        unfreezeRange = 30f * scale;
    }

    public void moveTo(Actor other, float delta) {
        Vector2 diff = new Vector2(other.getX(), other.getY()).sub(pos());

        diff.setLength(data.speed*delta);
        moveBy(diff.x, diff.y,delta);
    }

    public void initializeBaseMovementBehavior() {
        Location<Vector2> seekTarget = new Location<Vector2>() {
            @Override
            public Vector2 getPosition() {
                return navPath.nodes.get(0).pos;
            }

            @Override
            public float getOrientation() {
                return 0;
            }

            @Override
            public void setOrientation(float orientation) {

            }

            @Override
            public float vectorToAngle(Vector2 vector) {
                return 0;
            }

            @Override
            public Vector2 angleToVector(Vector2 outVector, float angle) {
                return null;
            }

            @Override
            public Location<Vector2> newLocation() {
                return null;
            }
        };
        Seek<Vector2> seek = new Seek<>(this);
        seek.setTarget(seekTarget);

        Array<Vector2> wp = new Array<>();
        if (navPath != null && navPath.nodes != null) {
            for (NavigationVertex v : navPath.nodes)
                wp.add(v.pos);
        }
        LinePath<Vector2> linePath = null;
        FollowPath<Vector2, LinePath.LinePathParam> followWaypoints = null;
        if (wp.size == 1) {
            wp.insert(0, pos());
        }
        if (wp.size >= 2) {
            linePath = new LinePath<Vector2>(wp, false);
            followWaypoints = new FollowPath<>(this, linePath);
            followWaypoints.setPathOffset(0.5f);
        }

        Arrive<Vector2> moveDirectlyToDestination = new Arrive<>(this, new Location<Vector2>() {
            @Override
            public Vector2 getPosition() {
                if (navPath == null || navPath.nodes.size == 0)
                    return pos();
                return navPath.get(0).pos;
            }

            @Override
            public float getOrientation() {
                return 0;
            }

            @Override
            public void setOrientation(float orientation) {

            }

            @Override
            public float vectorToAngle(Vector2 vector) {
                return 0;
            }

            @Override
            public Vector2 angleToVector(Vector2 outVector, float angle) {
                return null;
            }

            @Override
            public Location<Vector2> newLocation() {
                return null;
            }
        })
                .setTimeToTarget(0.01f)
                .setArrivalTolerance(0f)
                .setDecelerationRadius(10);

        if (followWaypoints != null)
            setBehavior(followWaypoints);
        else
            setBehavior(moveDirectlyToDestination);
    }

    public void setBehavior(SteeringBehavior<Vector2> behavior) {
        this.behavior = behavior;
    }

    public SteeringBehavior<Vector2> getBehavior() {
        return behavior;
    }

    public void update(float delta) {
        if(behavior != null) {
            behavior.calculateSteering(steerOutput);
            while (steerOutput.isZero() && navPath != null && navPath.getCount() > 1) {
                navPath.remove(0);
                behavior.calculateSteering(steerOutput);
            }
            applySteering(delta);
        }
    }

    private void applySteering(float delta) {
        if(!steerOutput.linear.isZero()) {
            Vector2 force = steerOutput.linear.scl(delta);
            force.setLength(Math.min(speed() * delta, force.len()));
            moveBy(force.x, force.y);
        }
    }

    @Override
    public float vectorToAngle (Vector2 vector) {
        return (float)Math.atan2(-vector.x, vector.y);
    }

    @Override
    public Vector2 angleToVector (Vector2 outVector, float angle) {
        outVector.x = -(float)Math.sin(angle);
        outVector.y = (float)Math.cos(angle);
        return outVector;
    }

    @Override
    public Vector2 getLinearVelocity() {
        return linearVelocity;
    }

    @Override
    public float getAngularVelocity() {
        return angularVelocity;
    }
    @Override
    public float getBoundingRadius() {
        return getWidth()/2;
    }

    @Override
    public boolean isTagged() {
        return tagged;
    }

    @Override
    public Vector2 getPosition() {
        return pos();
    }

    @Override
    public float getOrientation() {
        return orientation;
    }

    @Override
    public void setOrientation(float value) {
        orientation = value;
    }

    @Override
    public Location<Vector2> newLocation() {
        return null;
    }

    @Override
    public void setTagged(boolean value) {
        tagged = value;
    }

    public void freezeMovement(){
        _freeze = true;
        setPosition(_previousPosition6.x, _previousPosition6.y);
        // This will move the enemy back a few frames of movement.
        // Combined with player doing the same, should no longer be colliding to immediately re-enter battle if mob still present
    }

    public Vector2 getTargetVector(PlayerSprite player, ArrayList<NavigationVertex> sortedGraphNodes, float delta) {
        //todo - this can be integrated into overworld movement as well, giving flee behaviors or moving to generated waypoints
        Vector2 target = pos();
        Vector2 spriteToPlayer = new Vector2(player.pos()).sub(target);

        if (_freeze){
            //Mob has defeated player in battle, hold still until player has a chance to move away.
            //Without this moving enemies can immediately restart battle.
            float distance = spriteToPlayer.len();
            if (distance < unfreezeRange) {
                timer += delta;
                return Vector2.Zero;
            }
            else{
                _freeze = false; //resume normal behavior
            }
        }

        NavigationVertex targetPoint = null;
        if (threatRange > 0 || fleeRange > 0){
            if (spriteToPlayer.len() <= threatRange || (aggro && spriteToPlayer.len() <= pursueRange))
            {
                if (sortedGraphNodes != null) {
                    for (NavigationVertex candidate : sortedGraphNodes) {
                        Vector2 candidateToPlayer = new Vector2(candidate.pos).sub(player.pos());
                        if ((candidateToPlayer.x * candidateToPlayer.x) + (candidateToPlayer.y * candidateToPlayer.y) <
                                (spriteToPlayer.x * spriteToPlayer.x) + (spriteToPlayer.y * spriteToPlayer.y)) {
                            targetPoint = candidate;
                            break;
                        }
                    }
                }
                aggro = true;
                if (targetPoint != null) {
                    return targetPoint.pos;
                }
                return new Vector2(player.pos());
            }
            if (spriteToPlayer.len() <= fleeRange)
            {
                //todo: replace with inverse A* variant, seeking max total distance from player in X generations
                // of movement, valuing each node by distance from player divided by closest distance(s) in path
                // in order to make close passes to escape less appealing than maintaining moderate distance
                float fleeDistance = fleeRange - spriteToPlayer.len();
                return new Vector2(pos()).sub(player.pos()).setLength(fleeDistance).add(pos());
            }
            if (aggro && spriteToPlayer.len() > pursueRange) {
                aggro = false;
                if (navPath != null)
                    navPath.clear();
                initializeBaseMovementBehavior();
            }
        }

        if (movementBehaviors.peek() != null){
            MovementBehavior peek = movementBehaviors.peek();
            //TODO - This first block needs to be redone, doesn't work as intended and can also possibly skip behaviors in rare situations
//            if (peek.getDuration() == 0 && target.equals(_previousPosition6) && timer >= _movementTimeout)
//            {
//                //stationary in an untimed behavior, move on to next behavior attempt to get unstuck
//                if (movementBehaviors.size() > 1) {
//                    MovementBehavior current =  movementBehaviors.pop();
//                    current.currentTargetVector = null;
//                    movementBehaviors.addLast(current);
//                }
//            }
            //else
            if (peek.getDuration() == 0 && peek.getNextTargetVector(objectId, pos()).dst(pos()) < 2){
                //this is a location based behavior that has been completed. Move on to the next behavior

                    MovementBehavior current =  movementBehaviors.pop();
                    current.currentTargetVector = null;
                    movementBehaviors.addLast(current);

            }
            else if ( peek.getDuration() > 0)
            {
                if (timer >= peek.getDuration() + delta)
                {
                    //this is a timed behavior that has been completed. Move to the next behavior and restart the timer
                    MovementBehavior current =  movementBehaviors.pop();
                    current.currentTargetVector = null;
                    movementBehaviors.addLast(current);
                }
                else{
                    timer += delta;//this is a timed behavior that has not been completed, continue this behavior
                    return new Vector2(pos());
                }
            }
            if (peek.getNextTargetVector(objectId, pos()).dst(pos()) > 0.3) {
                target = new Vector2(peek.getNextTargetVector(objectId, pos()));
            }
            else target = new Vector2(pos());
        }
        else target = new Vector2(pos());
        return target;
    }
    public void updatePositon()
    {
        _previousPosition6.set(_previousPosition5);
        _previousPosition5.set(_previousPosition4);
        _previousPosition4.set(_previousPosition3);
        _previousPosition3.set(_previousPosition2);
        _previousPosition2.set(_previousPosition);
        _previousPosition.set(pos());
    }


    public EnemyData getData() {
        return data;
    }

    public void overrideDeck(String deckPath) {
        data.deck = new String[1];
        data.deck[0] = deckPath;
    }

    @Override
    public String getName() {
        if (nameOverride == null || nameOverride.isEmpty())
            return data.getName();
        return nameOverride;
    }

    /** Display-only tiered name, e.g. "Red Wizard (Adept)" - see EnemyData.getTieredDisplayName()
     *  for the convention/gating. Uses this sprite's own getName() so a map-authored nameOverride
     *  still shows, with the tier suffix applied on top of it. */
    public String getTieredDisplayName() {
        String base = getName();
        ConfigData config = Config.instance().getConfigData();
        if (config == null || !config.showEnemyTierInName)
            return base;
        String tierLabel = EnemyData.tierDisplayName(data.tier);
        if (base.startsWith(tierLabel + " ") && base.length() > tierLabel.length() + 1)
            base = base.substring(tierLabel.length() + 1);
        return base + " (" + tierLabel + ")";
    }
    public String getBossInsult(){
        return data.bossInsult;
    }
    public String getBossIntro(){
        return data.bossIntro;
    }
    public Array<Reward> getRewards() {
        Array<Reward> rewards = new Array<>();
        //Collect custom rewards for chaos battles

        if (data.copyPlayerDeck && Current.latestDeck() != null) {
            List<PaperCard> paperCardList = Current.latestDeck().getMain().toFlatList().stream()
                    .filter(paperCard -> !paperCard.isVeryBasicLand())
                    .collect(Collectors.toList());

            int uniqueRules = paperCardList.stream().map(PaperCard::getRules).collect(Collectors.toSet()).size();

            if (uniqueRules < 4 || paperCardList.size() < 10) {
                // Player trying to cheese doppleganger and farm cards. Sorry, the fun police have arrived
                // Static rewards of 199 GP, 9 Shards, and 1 Cheese Stands Alone
                rewards.add(new Reward(199));
                rewards.add(new Reward(Reward.Type.Shards, 9));

                PaperCard cheese = StaticData.instance().fetchCard("The Cheese Stands Alone");
                if (cheese != null) {
                    rewards.add(new Reward(cheese));
                }
                return rewards;
            }

            if (AdventurePlayer.current().isFantasyMode()) {
                //random uncommons from deck
                List<PaperCard> uncommonCards = paperCardList.stream()
                        .filter(paperCard -> paperCard.getRarity() == CardRarity.Uncommon || paperCard.getRarity() == CardRarity.Special)
                        .collect(Collectors.toList());
                if (!uncommonCards.isEmpty()) {
                    rewards.add(new Reward(Aggregates.random(uncommonCards)));
                    rewards.add(new Reward(Aggregates.random(uncommonCards)));
                }
                //random commons from deck
                List<PaperCard> commmonCards = paperCardList.stream()
                        .filter(paperCard -> paperCard.getRarity() == CardRarity.Common)
                        .collect(Collectors.toList());
                if (!commmonCards.isEmpty()) {
                    rewards.add(new Reward(Aggregates.random(commmonCards)));
                    rewards.add(new Reward(Aggregates.random(commmonCards)));
                    rewards.add(new Reward(Aggregates.random(commmonCards)));
                }
                //random rare from deck
                List<PaperCard> rareCards = paperCardList.stream()
                        .filter(paperCard -> paperCard.getRarity() == CardRarity.Rare || paperCard.getRarity() == CardRarity.MythicRare)
                        .collect(Collectors.toList());
                if (!rareCards.isEmpty()) {
                    rewards.add(new Reward(Aggregates.random(rareCards)));
                    rewards.add(new Reward(Aggregates.random(rareCards)));
                }

                int val = ((MyRandom.getRandom().nextInt(2)+1)*100)+(MyRandom.getRandom().nextInt(101));
                rewards.add(new Reward(val));
                rewards.add(new Reward(Reward.Type.Life, 1));

                return rewards;
            }
        }

        if(data.rewards != null) { //Collect standard rewards.
            Deck enemyDeck = Current.latestDeck();
            // By popular demand, remove basic lands from the reward pool.
            CardPool deckNoRestrictedEditions = enemyDeck.getMain().getFilteredPool(PaperCardPredicates.onlyPrintedInEditions(Config.instance().getConfigData().restrictedEditions).negate());
            CardPool deckNoBasicLands = deckNoRestrictedEditions.getFilteredPool(PaperCardPredicates.fromRules(CardRulesPredicates.NOT_BASIC_LAND));

            // Progressive Set Unlocks (MOD_SCOPE.md #4): ordinary roaming-monster loot is
            // restricted to that monster's color's shard - this is the actual discovery
            // mechanism the whole feature runs on (find cards from a color's assigned sets by
            // fighting that color, well before those sets are formally researched/unlocked).
            // Deliberately excludes bosses and quest-tagged enemies ("dedicated rewards/quest
            // rewards" per user spec) - only data.rewards (the generic template pool) gets
            // restricted, never this.rewards (per-instance overrides a few lines below, reserved
            // for genuinely special-cased encounters like the Deck Tester's AI shell).
            Iterable<RewardData> standardRewardSource = java.util.Arrays.asList(data.rewards);
            if (Current.world().isEditionProgressionEnabled()) {
                if (!data.boss && (data.questTags == null || data.questTags.length == 0)) {
                    String color = ColorReputation.singleColorOfEnemy(data.colors);
                    String colorLabel = color != null ? color : EditionProgression.NEUTRAL;
                    List<String> editionRestriction = EditionProgression.getEditionsForColor(Current.world(), colorLabel);
                    // Diagnostic-only logging - greppable in forge.log as "[TFR-LootEditions]".
                    System.out.println("[TFR-LootEditions] enemy=" + data.name + " colors=" + data.colors
                            + " -> " + colorLabel + " restriction(" + editionRestriction.size() + ")=" + editionRestriction);
                    standardRewardSource = EditionProgression.restrictToEditions(standardRewardSource, editionRestriction);
                } else {
                    // Diagnostic logging (2026-08-13) - this exemption (dedicated boss/quest
                    // rewards deliberately skip edition restriction, per user spec) previously
                    // fired completely silently, making "exempted by design" indistinguishable
                    // from "this code path never ran" when grepping forge.log for a specific
                    // enemy.
                    System.out.println("[TFR-LootEditions] enemy=" + data.name + " colors=" + data.colors
                            + " -> EXEMPT boss=" + data.boss
                            + " questTagged=" + (data.questTags != null && data.questTags.length > 0));
                }
            }
            for (RewardData rdata : standardRewardSource) {
                rewards.addAll(rdata.generate(false,  enemyDeck == null ? null : deckNoBasicLands.toFlatList(),true ));
            }
        }
        if(this.rewards != null) { //Collect additional rewards.
            for(RewardData rdata : this.rewards) {
                //Do not filter in case we want to FORCE basic lands. If it ever becomes a problem just repeat the same as above.

                rewards.addAll(rdata.generate(false,(Current.latestDeck() != null ? Current.latestDeck().getMain().toFlatList() : null), true));
            }
        }
        applyGoldVariance(rewards);
        return rewards;
    }

    // Combat reward variance (2026-08-09, user spec): 25% of the time a Gold reward is swapped
    // for Wood or Stone instead (50/50 between the two), at 50% of the gold amount it would have
    // been.
    //
    // Originally this REMOVED the gold reward from the array and granted the resource directly,
    // because at the time Wood/Stone had no Reward.Type and no art in items.atlas, so they could
    // not be shown as loot tiles. Both of those constraints are long gone - Reward.Type.Stone/Wood
    // were added 2026-08-10/11 (see RewardData's "stone"/"wood" cases) and RewardActor grew the
    // matching icon case 2026-08-27 - but this method was never revisited, so a quarter of all
    // duel gold silently turned into resources the player was never shown (user report
    // 2026-08-29: "we kinda jimmy rigged how you got wood and stone from wining duels... it was
    // just added, without showing you any icons"). The old floating status message it used was
    // effectively invisible anyway: it passed a null icon AND attached itself to the world/map
    // stage that the caller switches away from a few statements later to show RewardScene.
    //
    // Now substituted IN PLACE, so the resource rides the ordinary loot-tile path: RewardScene
    // gives it its own tile, RewardActor draws the items.atlas glyph, and the grant happens on
    // dismiss via clearGenerated() -> AdventurePlayer.addReward(). Same substitution shape
    // RewardData.shardsSubstituteType() already uses for shards->Stone/Wood.
    private static final float GOLD_VARIANCE_CHANCE = 0.25f;

    private void applyGoldVariance(Array<Reward> rewards) {
        for (int i = rewards.size - 1; i >= 0; i--) {
            Reward reward = rewards.get(i);
            if (reward.getType() != Reward.Type.Gold)
                continue;
            if (MyRandom.getRandom().nextFloat() >= GOLD_VARIANCE_CHANCE)
                continue;
            boolean wood = MyRandom.getRandom().nextBoolean();
            int amount = Math.max(1, reward.getCount() / 2);
            rewards.set(i, new Reward(wood ? Reward.Type.Wood : Reward.Type.Stone, amount));
        }
    }

    private void drawColorHints(Batch batch){
        int size = Math.min(data.colors.length(), 6);
        float DX = getX() - 2f;
        float DY = getY();

        for(int i = 0; i < size; i++){
            char C = data.colors.toUpperCase().charAt(i);
            switch (C) {
                default: break;
                case 'C': {
                    batch.setColor(Color.DARK_GRAY);
                    batch.draw(Forge.getAssets().getWhiteTexture(), DX, DY, 2, 2);
                    DY += 2; break;
                }
                case 'B': {
                    batch.setColor(Color.PURPLE);
                    batch.draw(Forge.getAssets().getWhiteTexture(), DX, DY, 2, 2);
                    DY += 2; break;
                }
                case 'G': {
                    batch.setColor(Color.GREEN);
                    batch.draw(Forge.getAssets().getWhiteTexture(), DX, DY, 2, 2);
                    DY += 2; break;
                }
                case 'R': {
                    batch.setColor(Color.RED);
                    batch.draw(Forge.getAssets().getWhiteTexture(), DX, DY, 2, 2);
                    DY += 2; break;
                }
                case 'U': {
                    batch.setColor(Color.BLUE);
                    batch.draw(Forge.getAssets().getWhiteTexture(), DX, DY, 2, 2);
                    DY += 2; break;
                }
                case 'W': {
                    batch.setColor(Color.WHITE);
                    batch.draw(Forge.getAssets().getWhiteTexture(), DX, DY, 2, 2);
                    DY += 2; break;
                }
            }
        }
        batch.setColor(Color.WHITE);
    }

    @Override
    public void draw(Batch batch, float parentAlpha) {
        if (inactive || hidden)
            return;
        // Fog of war (MOD_SCOPE.md #3): on the overworld, a "known" tile only means you remember
        // the terrain - it doesn't mean you can see what's moving around on it right now. Monsters
        // only render within the live vision radius. Town/dungeon (MapStage) enemies are unaffected
        // - fog only applies to the overworld, and their coordinates aren't overworld tile
        // coordinates in the first place.
        if (!MapStage.getInstance().isInMap()) {
            World world = Current.world();
            int tileSize = world.getTileSize();
            int tileX = (int) (getX() / tileSize);
            int tileY = (int) (getY() / tileSize);
            // isCurrentlyVisible() also covers persistently-revealed owned territory (World.java's
            // isPersistentlyRevealed()), which paints ownership without ever marking explored[][] -
            // so a tile can pass isCurrentlyVisible() while the terrain under it still renders as
            // unexplored black. Require both, or a monster floats fully lit over solid black ground.
            if (!world.isExploredWorld(tileX, tileY) || !world.isCurrentlyVisible(tileX, tileY))
                return;
        }
        super.draw(batch, parentAlpha);
        if(Current.player().hasColorView() && !data.colors.isEmpty()) {
            drawColorHints(batch);
        }
        if(dialog != null && dialog.canShow()){ //Draw a talk icon on top.
            Texture T = Current.world().getGlobalTexture();
            TextureRegion TR = new TextureRegion(T, 0, 0, 16, 16);
            batch.draw(TR, getX(), getY() + 16, 16, 16);
        }
        if(effect != null){ //Draw a crown icon on top.
            Texture T = Current.world().getGlobalTexture();
            TextureRegion TR = new TextureRegion(T, 16, 0, 16, 16);
            batch.draw(TR, getX(), getY() + 16, 16*getScaleX(), 16*getScaleY());
        }
    }

    public float speed() {
        return Float.max(data.speed + speedModifier, 0);
    }

    public float getLifetime() {
        //default and minimum value for time to remain on overworld map
        float lifetime = 20f;
        return Math.max(data.lifetime, lifetime);
    }

    //Pathfinding integration below this line

    public void setNavPath(ProgressableGraphPath<NavigationVertex> navPath) {
        this.navPath = navPath;
    }

    public ProgressableGraphPath<NavigationVertex> getNavPath() {
        return navPath;
    }

    @Override
    public float getZeroLinearSpeedThreshold() {
        return 0;
    }

    @Override
    public void setZeroLinearSpeedThreshold(float value) {

    }

    @Override
    public float getMaxLinearSpeed() {
        return 500;
    }

    @Override
    public void setMaxLinearSpeed(float maxLinearSpeed) {

    }

    @Override
    public float getMaxLinearAcceleration() {
        return 5000;
    }

    @Override
    public void setMaxLinearAcceleration(float maxLinearAcceleration) {

    }

    @Override
    public float getMaxAngularSpeed() {
        return 0;
    }

    @Override
    public void setMaxAngularSpeed(float maxAngularSpeed) {

    }

    @Override
    public float getMaxAngularAcceleration() {
        return 0;
    }

    @Override
    public void setMaxAngularAcceleration(float maxAngularAcceleration) {

    }

    public void steer(Vector2 currentVector) {

    }

    public boolean isFrozen() {
        return _freeze;
    }
}

