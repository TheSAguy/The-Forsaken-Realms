package forge.adventure.util;

import forge.adventure.stage.GameHUD;
import com.badlogic.gdx.math.Vector2;
import forge.adventure.character.CharacterSprite;
import forge.adventure.character.EnemySprite;
import forge.adventure.data.RoamingGuardData;
import forge.adventure.player.AdventurePlayer;
import forge.adventure.pointofintrest.PointOfInterest;
import forge.adventure.stage.SpriteGroup;
import forge.adventure.world.WorldSave;
import org.apache.commons.lang3.tuple.Pair;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The overworld half of roaming guards (MOD_SCOPE #116, round 145): dispatch, travel, and the
 * moment of interception. The dialogs live in {@link RoamingGuardUI}, the roster and rules in
 * {@link RoamingGuards}, and only actor plumbing is left in WorldStage - the same split Territory
 * Control already uses, where WorldStage moves the mage and TerritoryControl decides everything
 * about it.
 *
 * <h3>How a defence actually plays out</h3>
 * A guard does not chase the mage across open ground. It races it <b>to the town</b>, which is what
 * the user asked for and is also the only version that can work: mages run 20-30 speed at
 * Common/Uncommon/Rare but 50-60 at Mythic, and a guard tops out at the player's own speed. A stern
 * chase against the dangerous ones would never succeed. Racing to a fixed destination can, because
 * the guard may start closer - and a teleporter at the target town lets it skip the race entirely,
 * which is the intended counterplay to a Mythic attack.
 * <p>
 * So: the guard runs to the threatened town and waits at the gate. If it is standing there when the
 * mage arrives, it intercepts. If the mage gets there first, the town defends itself exactly as it
 * always has and the guard turns for home.
 *
 * <h3>Why launching a duel from here is safe</h3>
 * Everything in this class runs from WorldStage's acting loop, inside the block guarded by
 * {@code player.isMoving() || waitingForTime}. In-game time only advances there, so a mage can only
 * ever arrive - and a guard can only ever intercept - while the player is on the overworld and not
 * in a dialog. That is the same context an ordinary roaming-monster collision launches a duel from.
 */
public class RoamingGuardRuntime {
    private RoamingGuardRuntime() {}

    /** How close a guard must be to its destination to count as arrived. Matches WorldStage's own
     *  TERRITORY_ARRIVAL_EPSILON so a guard and a mage "reach" a town on the same terms. */
    private static final float ARRIVAL_EPSILON = 8f;

    /** Live sprites, keyed by the guard they belong to. Rebuilt from the roster every frame, so a
     *  load, a dismissal or a New Game+ cannot leave an orphan on the map. */
    private static final Map<RoamingGuardData, CharacterSprite> sprites = new HashMap<>();

    // ------------------------------------------------------------------ off duty (round 233)
    // User: "For the roaming guards, If they are not out on assignment, they should wander outside of the
    // Capitol. This will be cosmetic only, since they won't interact with any enemies in the area. (Unless
    // defending capitol) Just have them move around outside the capitol."
    //
    // A guard at rest used to have no sprite at all. Now it strolls a ring around the Capitol: walk to a
    // nearby point, stand a few seconds, walk on. COSMETIC BY CONSTRUCTION:
    //  - the sprite is a plain CharacterSprite in foregroundSprites, exactly like a travelling guard's. It
    //    is not in WorldStage's enemy list, so no monster, mage or player can collide with or fight it;
    //  - where it stands lives in a transient Stroll, never in the persisted guard.x/y, so a save taken
    //    mid-stroll is byte-for-byte the save it would have been, and a load simply re-places the guards;
    //  - it uses MathUtils.random, NOT the world's seeded Random, so strolling cannot shift a single
    //    gameplay roll;
    //  - missions are untouched. An attack on the Capitol itself is an ordinary mission whose target happens
    //    to be home (the Capitol carries TOWN_RESTORED_FLAG), so "defending the Capitol" already works: the
    //    stroller is dispatched, walks the few steps to the gate, and intercepts there.
    // The one thing a stroll does feed back: a dispatched guard sets out from where it was standing rather
    // than from the Capitol's origin, so the sprite does not jump. That moves its start by at most the ring's
    // radius - a second or two on a journey of minutes, and as often nearer the target as farther.
    // Like everything else on the overworld it only moves while the player does (update() runs inside
    // WorldStage's time block); standStill() drops every guard to Idle when the world stops.
    private static final class Stroll {
        float x, y, goalX, goalY, pause;
        boolean hasGoal;
    }

    private static final Map<RoamingGuardData, Stroll> strolls = new HashMap<>();
    /** A stroll is a fraction of the guard's travel speed - someone off duty, not someone racing a mage. */
    private static final float STROLL_SPEED_FACTOR = 0.35f;
    /** The ring: from this far beyond the Capitol's half-diagonal (so never inside the building) ... */
    private static final float STROLL_RING_INNER = 10f;
    /** ... to this much farther out. */
    private static final float STROLL_RING_WIDTH = 44f;
    /** Each new goal is at most this far round the ring from where the guard stands, so its straight path
     *  stays outside the building instead of cutting through it to the far side. */
    private static final float STROLL_MAX_TURN_DEGREES = 70f;
    private static final float STROLL_PAUSE_MIN_SECONDS = 1.5f;
    private static final float STROLL_PAUSE_MAX_SECONDS = 5f;
    /** Half a character sprite's width: ring points are where the guard's feet go, not its left edge. */
    private static final float STROLL_SPRITE_HALF_WIDTH = 8f;
    private static final com.badlogic.gdx.math.Rectangle strollProbe = new com.badlogic.gdx.math.Rectangle();

    /** Set while a guard duel is being fought, so WorldStage.setWinner() knows to route the result
     *  here instead of treating it as one of the player's own fights. */
    private static RoamingGuardData duellingGuard;
    private static EnemySprite duellingMage;
    /** Round 166: the mage told to wait at its gate while another guard fight runs - so the wait is
     *  logged once, and so a guard that wins can hold the gate for it instead of walking home. */
    private static EnemySprite waitingMage;

    /** What a mage arriving at its target town meets: a guard's duel, nothing, or a queue. */
    public enum Arrival { FIGHT, PASS, WAIT }

    public static RoamingGuardData duellingGuard() {
        return duellingGuard;
    }

    /** Round 173 (review G6): the mage being fought right now. It is off WorldStage's enemy list until
     *  the result is in, so WorldStage.save() writes it back at the gate - see resolveInterruptedDuels(). */
    public static EnemySprite duellingMage() {
        return duellingMage;
    }

    public static void clearDuel() {
        duellingGuard = null;
        duellingMage = null;
    }

    // ------------------------------------------------------------------ per-frame

    /**
     * Called once per acting frame from WorldStage, after the mage movement loop.
     *
     * @param enemies           WorldStage's live enemy list - read only, to find in-flight mages
     * @param foregroundSprites where guard actors are added and removed
     */
    public static void update(float delta, List<Pair<Float, EnemySprite>> enemies, SpriteGroup foregroundSprites) {
        if (!RoamingGuards.isEnabled())
            return;
        int day = WorldSave.getCurrentSave().getWorld().getCurrentDay();
        RoamingGuards.processDailyTick(day);
        assignMissions(enemies, day);
        // ROUND 152 BUG FIX (user playtest: a guard that won at a town "teleported directly from
        // the town" instead of walking home). delta is the RAW frame time, and the first frame back
        // from a duel carries however long that match took - `speed * delta` then dwarfs the whole
        // remaining journey and the Math.min() in moveGuards snaps the guard to its destination in
        // a single step. The mages never showed this because a duel starts from a collision at
        // their destination; a guard is mid-journey across the scene switch.
        // Round 183 (code review G11): clamp only a STALL (a scene switch, a duel, a dialog), not a slow frame -
        // clamping every frame to 0.05 s made a guard cover 75% of its speed at 15 fps while the mage it races
        // moves on the raw frame time.
        moveGuards(delta > STALL_SECONDS ? MAX_STEP_SECONDS : delta, day, foregroundSprites);
    }

    /** Give every unattended attack on a player town to an available guard. */
    private static void assignMissions(List<Pair<Float, EnemySprite>> enemies, int day) {
        // First, release any guard whose threat has evaporated - the player can kill a mage on the
        // road, and a mage can be despawned or re-targeted. Without this the guard would stand at
        // the gate of a town nobody is attacking, permanently unavailable for the next threat.
        java.util.Set<String> liveThreats = new java.util.HashSet<>();
        for (Pair<Float, EnemySprite> pair : enemies) {
            if (pair.getValue().territoryTarget != null)
                liveThreats.add(pair.getValue().territoryTarget.getID());
        }
        for (RoamingGuardData guard : RoamingGuards.roster()) {
            if (guard.isIdle() || guard.returningHome)
                continue;
            if (guard == duellingGuard)
                continue; // round 152: its mage is off the enemies list PRECISELY because it is
                          // being fought right now - releasing the guard here logged "target is no
                          // longer under attack" in the middle of its own interception.
            if (!liveThreats.contains(guard.missionPoiId)) {
                System.out.println("[TFR-RoamGuard] " + RoamingGuards.displayName(guard.tier)
                        + "'s target is no longer under attack - returning to the Capitol");
                sendHome(guard);
            }
        }
        for (Pair<Float, EnemySprite> pair : enemies) {
            EnemySprite mage = pair.getValue();
            if (mage.territoryTarget == null)
                continue;
            PointOfInterest target = mage.territoryTarget;
            if (!isPlayerOwned(target))
                continue;
            String targetId = target.getID();
            if (guardAssignedTo(targetId) != null)
                continue; // one guard per threat
            RoamingGuardData guard = pickGuardFor(mage, day);
            if (guard == null)
                continue;
            guard.missionPoiId = targetId;
            guard.returningHome = false;
            guard.deployed = true;
            // Round 233: a guard that was strolling sets out from where it stands, so its sprite walks
            // off instead of jumping to the Capitol's origin first - see the off-duty notes above.
            Stroll stroll = strolls.remove(guard);
            if (stroll != null) {
                guard.x = stroll.x;
                guard.y = stroll.y;
            }
            // A guard at rest sits at the Capitol, so that is where it sets out from. Teleporting
            // is decided on arrival at the destination, not here, so the log reads in order.
            PointOfInterest home = RoamingGuards.capitol();
            if (guard.x == 0 && guard.y == 0 && home != null) {
                guard.x = home.getPosition().x;
                guard.y = home.getPosition().y;
            }
            boolean teleports = canTeleportTo(target);
            if (teleports) {
                guard.x = target.getPosition().x;
                guard.y = target.getPosition().y;
            }
            System.out.println("[TFR-RoamGuard] " + RoamingGuards.displayName(guard.tier) + " dispatched to "
                    + target.getDisplayName() + " against a " + mage.getData().tier + " mage (speed "
                    + mage.getData().speed + " vs " + (int) (RoamingGuards.speedFor(guard.tier) * ArmoryStorage.speedOf(guard))
                    + ", gear " + ArmoryStorage.gearNames(guard) + ")"
                    + (teleports ? " - TELEPORTED, already in position" : " - travelling"));
            GameHUD.getInstance().addNotification("Your " + RoamingGuards.displayName(guard.tier)
                    + " guard sets out for " + target.getDisplayName() + "."
                    + (teleports ? " (teleported)" : ""));
        }
    }

    /**
     * The guard that will take this threat, or null. Skips guards that are busy, out of commission,
     * carrying no deck, or whose engagement rules say to avoid this rank OR colour of enemy.
     */
    private static RoamingGuardData pickGuardFor(EnemySprite mage, int day) {
        String enemyTier = mage.getData().tier;
        for (RoamingGuardData guard : RoamingGuards.roster()) {
            if (!guard.isIdle() || guard.returningHome)
                continue;
            if (guard.isOutOfCommission(day))
                continue;
            if (guard.deckCards.length == 0)
                continue; // nothing to fight with
            if (!RoamingGuards.willEngage(guard, enemyTier))
                continue; // the player told it to avoid this rank
            if (!RoamingGuards.willEngageColor(guard, mage.territoryColor))
                continue; // round 148 - and to leave this colour alone
            return guard;
        }
        return null;
    }

    private static void moveGuards(float delta, int day, SpriteGroup foregroundSprites) {
        PointOfInterest home = RoamingGuards.capitol();
        List<RoamingGuardData> roster = RoamingGuards.roster();
        // Drop sprites for guards that are gone or downed. Round 233: a guard that is merely not deployed
        // keeps its sprite now - it is strolling outside the Capitol.
        List<RoamingGuardData> stale = new ArrayList<>();
        for (Map.Entry<RoamingGuardData, CharacterSprite> entry : sprites.entrySet()) {
            RoamingGuardData guard = entry.getKey();
            if (!roster.contains(guard) || guard.isOutOfCommission(day)
                    || (!guard.deployed && !isOffDuty(guard, day, home)))
                stale.add(guard);
        }
        for (RoamingGuardData guard : stale) {
            CharacterSprite sprite = sprites.remove(guard);
            if (sprite != null)
                foregroundSprites.removeActor(sprite);
        }
        strolls.keySet().removeIf(guard -> !roster.contains(guard) || !isOffDuty(guard, day, home));

        for (RoamingGuardData guard : roster) {
            if (guard.isOutOfCommission(day))
                continue;
            if (!guard.deployed) {
                if (isOffDuty(guard, day, home))
                    strollStep(guard, home, delta, foregroundSprites); // round 233
                continue;
            }
            PointOfInterest destination = guard.isIdle() ? home : poiById(guard.missionPoiId);
            if (destination == null) {
                guard.deployed = false;
                continue;
            }
            Vector2 goal = destination.getPosition();
            float stepX = 0f, stepY = 0f;
            float speed = RoamingGuards.speedFor(guard.tier) * ArmoryStorage.speedOf(guard); // round 163: boots count
            float dx = goal.x - guard.x;
            float dy = goal.y - guard.y;
            float distance = (float) Math.sqrt(dx * dx + dy * dy);
            if (distance <= ARRIVAL_EPSILON) {
                if (guard.returningHome) {
                    // Home again, and available for the next threat (user spec: "After winning, it
                    // will first go back to the capitol, then dispatch to the next town").
                    guard.returningHome = false;
                    guard.deployed = false;
                    guard.missionPoiId = "";
                    System.out.println("[TFR-RoamGuard] " + RoamingGuards.displayName(guard.tier)
                            + " is home at the Capitol and available again");
                    // Round 233: it starts its stroll from the gate it just walked up to, not from a
                    // random spot on the ring - that is for guards that were already home (a load, a hire).
                    Stroll fromTheGate = new Stroll();
                    fromTheGate.x = guard.x;
                    fromTheGate.y = guard.y;
                    fromTheGate.pause = STROLL_PAUSE_MIN_SECONDS;
                    strolls.put(guard, fromTheGate);
                }
                // Otherwise it is standing at the threatened town, waiting to intercept.
            } else {
                float step = Math.min(distance, speed * delta);
                stepX = dx / distance * step;
                stepY = dy / distance * step;
                guard.x += stepX;
                guard.y += stepY;
            }

            CharacterSprite sprite = spriteFor(guard, guard.x, guard.y, foregroundSprites);
            // Round 156 (user: "the guards... appear to just have a static image moving"). They
            // were positioned with setPosition(), which moves the actor and nothing else, so every
            // guard sat on its constructor's Idle frame forever. moveBy() is what the mages use -
            // it picks the Walk animation AND the eight-way facing from the movement vector - so
            // the walk is driven by the same code the rest of the overworld uses.
            if (stepX != 0f || stepY != 0f)
                sprite.moveBy(stepX, stepY, delta);
            else
                sprite.setAnimation(CharacterSprite.AnimationTypes.Idle);
            sprite.setPosition(guard.x, guard.y); // authoritative: guard.x/y is what persists
        }
    }

    /** The live sprite for this guard, created where it stands the first time it is needed. */
    private static CharacterSprite spriteFor(RoamingGuardData guard, float x, float y, SpriteGroup foregroundSprites) {
        CharacterSprite sprite = sprites.get(guard);
        if (sprite == null) {
            sprite = new CharacterSprite(AdventurePlayer.current().spriteName());
            sprite.setTierCue(guard.tier); // round 160: the same rank size cue as the mages it races
            sprites.put(guard, sprite);
            foregroundSprites.addActor(sprite);
            sprite.setPosition(x, y);
        }
        return sprite;
    }

    /** Round 233: at home, fit for duty, and with nothing to do - the guards that stroll. A guard with no
     *  deck strolls too: it cannot take a fight, but it is still standing around the Capitol. */
    private static boolean isOffDuty(RoamingGuardData guard, int day, PointOfInterest home) {
        return home != null && guard.isIdle() && !guard.deployed && !guard.returningHome
                && !guard.isOutOfCommission(day);
    }

    /** Round 233: one frame of an off-duty guard's stroll - see the notes on the Stroll class. */
    private static void strollStep(RoamingGuardData guard, PointOfInterest home, float delta, SpriteGroup foregroundSprites) {
        Stroll stroll = strolls.get(guard);
        if (stroll == null) {
            // Already home when we first see it (a load, a new hire, a recovery): put it somewhere on the
            // ring rather than have every guard walk out of the same corner of the Capitol together, and
            // give each its own first pause so they do not move in step either.
            stroll = new Stroll();
            com.badlogic.gdx.math.Rectangle box = home.getBoundingRectangle();
            stroll.x = box.x + box.width / 2f - STROLL_SPRITE_HALF_WIDTH;
            stroll.y = box.y - STROLL_RING_INNER; // fallback: just below the gate
            pickStrollPoint(stroll, home, false);
            if (stroll.hasGoal) {
                stroll.x = stroll.goalX;
                stroll.y = stroll.goalY;
                stroll.hasGoal = false;
            }
            stroll.pause = com.badlogic.gdx.math.MathUtils.random(0f, STROLL_PAUSE_MAX_SECONDS);
            strolls.put(guard, stroll);
            System.out.println("[TFR-RoamGuard] " + RoamingGuards.displayName(guard.tier)
                    + " is off duty - strolling outside the Capitol (cosmetic; " + strolls.size() + " strolling)");
        }
        float stepX = 0f, stepY = 0f;
        if (stroll.pause > 0f) {
            stroll.pause -= delta;
        } else {
            if (!stroll.hasGoal)
                pickStrollPoint(stroll, home, true);
            if (!stroll.hasGoal) {
                stroll.pause = STROLL_PAUSE_MIN_SECONDS; // nowhere walkable this time - look again shortly
            } else {
                float dx = stroll.goalX - stroll.x, dy = stroll.goalY - stroll.y;
                float distance = (float) Math.sqrt(dx * dx + dy * dy);
                if (distance <= 1f) {
                    stroll.hasGoal = false;
                    stroll.pause = com.badlogic.gdx.math.MathUtils.random(STROLL_PAUSE_MIN_SECONDS, STROLL_PAUSE_MAX_SECONDS);
                } else {
                    float step = Math.min(distance, RoamingGuards.speedFor(guard.tier) * STROLL_SPEED_FACTOR * delta);
                    stepX = dx / distance * step;
                    stepY = dy / distance * step;
                    stroll.x += stepX;
                    stroll.y += stepY;
                }
            }
        }
        CharacterSprite sprite = spriteFor(guard, stroll.x, stroll.y, foregroundSprites);
        if (stepX != 0f || stepY != 0f)
            sprite.moveBy(stepX, stepY, delta); // the Walk animation and the facing - see moveGuards()
        else
            sprite.setAnimation(CharacterSprite.AnimationTypes.Idle);
        sprite.setPosition(stroll.x, stroll.y);
    }

    /**
     * Round 233: choose the next point on the ring around the Capitol, or leave {@code hasGoal} false when
     * eight tries find nothing walkable (a Capitol hemmed in by water or mountains).
     *
     * @param nearby true to stay within STROLL_MAX_TURN_DEGREES of where the guard stands (a stroll leg);
     *               false for anywhere on the ring (first placement)
     */
    private static void pickStrollPoint(Stroll stroll, PointOfInterest home, boolean nearby) {
        com.badlogic.gdx.math.Rectangle box = home.getBoundingRectangle();
        float centerX = box.x + box.width / 2f, centerY = box.y + box.height / 2f;
        float inner = (float) Math.sqrt(box.width * box.width + box.height * box.height) / 2f + STROLL_RING_INNER;
        float here = com.badlogic.gdx.math.MathUtils.atan2(stroll.y - centerY, stroll.x + STROLL_SPRITE_HALF_WIDTH - centerX);
        forge.adventure.world.World world = WorldSave.getCurrentSave().getWorld();
        stroll.hasGoal = false;
        for (int attempt = 0; attempt < 8; attempt++) {
            float angle = nearby
                    ? here + com.badlogic.gdx.math.MathUtils.random(-STROLL_MAX_TURN_DEGREES, STROLL_MAX_TURN_DEGREES)
                            * com.badlogic.gdx.math.MathUtils.degreesToRadians
                    : com.badlogic.gdx.math.MathUtils.random(0f, com.badlogic.gdx.math.MathUtils.PI2);
            float radius = inner + com.badlogic.gdx.math.MathUtils.random(0f, STROLL_RING_WIDTH);
            float goalX = centerX + com.badlogic.gdx.math.MathUtils.cos(angle) * radius - STROLL_SPRITE_HALF_WIDTH;
            float goalY = centerY + com.badlogic.gdx.math.MathUtils.sin(angle) * radius;
            if (!strollable(world, goalX, goalY))
                continue;
            if (nearby && !strollable(world, (stroll.x + goalX) / 2f, (stroll.y + goalY) / 2f))
                continue; // the goal is dry land but the way there crosses water
            stroll.goalX = goalX;
            stroll.goalY = goalY;
            stroll.hasGoal = true;
            return;
        }
    }

    /** Round 233: can a guard stand here - inside the world and not on a colliding tile (water, peaks). */
    private static boolean strollable(forge.adventure.world.World world, float x, float y) {
        if (x < 0f || y < 0f || x + 16f > world.getWidthInPixels() || y + 8f > world.getHeightInPixels())
            return false;
        return !world.collidingTile(strollProbe.set(x, y, 16f, 8f));
    }

    /**
     * Round 233: the world has stopped (the player is standing still, so WorldStage is not calling
     * update()). Every guard sprite drops to Idle, the way WorldStage already idles its enemies - without
     * this a guard caught mid-step kept playing its Walk cycle on the spot.
     */
    public static void standStill() {
        for (CharacterSprite sprite : sprites.values())
            sprite.setAnimation(CharacterSprite.AnimationTypes.Idle);
    }

    // ------------------------------------------------------------------ interception

    /**
     * Called from WorldStage the instant a mage reaches its target town, BEFORE
     * TerritoryControl.onMageArrived(). Returns true when a guard was standing there and has taken
     * the fight - the caller must then stop processing that mage, because the duel decides whether
     * it ever reaches the town at all.
     */
    /** One frame's worth of travel at most. A duel, a dialog or a stutter must not become
     *  distance covered - see update()'s note. */
    private static final float MAX_STEP_SECONDS = 0.05f;
    /** Round 183: a frame longer than this is a stall, not a slow frame - see update(). */
    private static final float STALL_SECONDS = 0.25f;

    public static Arrival onArrival(EnemySprite mage) {
        if (!RoamingGuards.isEnabled() || mage.territoryTarget == null)
            return Arrival.PASS;
        RoamingGuardData guard = guardAssignedTo(mage.territoryTarget.getID());
        if (guard == null || guard.returningHome || guard.deckCards.length == 0)
            return Arrival.PASS;
        Vector2 town = mage.territoryTarget.getPosition();
        float dx = town.x - guard.x, dy = town.y - guard.y;
        if (dx * dx + dy * dy > ARRIVAL_EPSILON * ARRIVAL_EPSILON) {
            // Still on the road. The town defends itself as usual and the guard turns for home,
            // "regardless of what happened at the town at that point" (user spec).
            System.out.println("[TFR-RoamGuard] " + RoamingGuards.displayName(guard.tier)
                    + " did not reach " + mage.territoryTarget.getDisplayName()
                    + " in time - the town defends itself, guard returning home");
            GameHUD.getInstance().addNotification("[RED]Your guard did not reach "
                    + mage.territoryTarget.getDisplayName() + " in time.");
            sendHome(guard);
            return Arrival.PASS;
        }
        if (duellingGuard != null) {
            // Round 166 (user: "Should just be queued?"): one guard fight at a time - a headless one
            // takes up to 90 seconds of world time, and a second mage arriving meanwhile used to walk
            // in unopposed. It waits at the gate instead: the caller leaves it standing there and asks
            // again next frame, so the moment the running fight resolves this one starts.
            if (waitingMage != mage) {
                waitingMage = mage;
                System.out.println("[TFR-RoamGuard] " + mage.getData().tier + " mage waits at "
                        + mage.territoryTarget.getDisplayName() + " - " + RoamingGuards.displayName(duellingGuard.tier)
                        + " is still fighting" + (guard == duellingGuard ? " here" : " elsewhere"));
            }
            return Arrival.WAIT;
        }
        if (waitingMage == mage)
            waitingMage = null;
        duellingGuard = guard;
        duellingMage = mage;
        guard.inDuel = true; // round 173: persisted until the result is in - see resolveInterruptedDuels()
        System.out.println("[TFR-RoamGuard] " + RoamingGuards.displayName(guard.tier) + " intercepts a "
                + mage.getData().tier + " mage at " + mage.territoryTarget.getDisplayName()
                + " (guard life " + guard.maxLife + ", deck \"" + guard.deckName + "\", "
                + (guard.watchMatches ? "watched" : "simulated") + ")");
        return Arrival.FIGHT;
    }

    /**
     * Result of the interception duel.
     *
     * @param guardWon true when the guard's seat won
     * @return the mage to let through to the town, or null when the attack was stopped
     */
    public static EnemySprite onDuelFinished(boolean guardWon) {
        RoamingGuardData guard = duellingGuard;
        EnemySprite mage = duellingMage;
        clearDuel();
        if (guard == null)
            return null;
        guard.inDuel = false;
        int day = WorldSave.getCurrentSave().getWorld().getCurrentDay();
        String town = mage != null && mage.territoryTarget != null
                ? mage.territoryTarget.getDisplayName() : "your town";
        if (guardWon) {
            System.out.println("[TFR-RoamGuard] " + RoamingGuards.displayName(guard.tier)
                    + " WON at " + town + " - the attack is broken");
            GameHUD.getInstance().addNotification("[GREEN]Your " + RoamingGuards.displayName(guard.tier)
                    + " guard broke the attack on " + town + "!", true);
            // Round 166: another attacker already waiting at this same gate? Hold it rather than
            // walking home - the queue would otherwise find the guard "returning" and let the mage in.
            boolean anotherAtTheGate = waitingMage != null && waitingMage.territoryTarget != null && mage != null
                    && mage.territoryTarget != null
                    && waitingMage.territoryTarget.getID().equals(mage.territoryTarget.getID());
            if (anotherAtTheGate)
                System.out.println("[TFR-RoamGuard] " + RoamingGuards.displayName(guard.tier) + " holds the gate at "
                        + town + " - another attacker is waiting");
            else
                sendHome(guard);
            return null;
        }
        RoamingGuards.onDefeated(guard, day);
        GameHUD.getInstance().addNotification("[RED]Your " + RoamingGuards.displayName(guard.tier)
                + " guard fell at " + town + " and is out of commission for "
                + RoamingGuards.recoveryDays() + " days.", true);
        return mage; // the mage carries on to the town, which now defends itself
    }

    /**
     * Round 183 (code review G16): a fight that cannot happen - the guard has no deck to play, or the mage has
     * none - is no contest. The guard walks home unhurt (it used to be scored a LOSS and benched for a month,
     * against the "let the town defend itself" the callers' comments promised) and the mage goes on to the
     * town, which defends itself as if no guard had been there.
     *
     * @return the mage to hand to the town
     */
    public static EnemySprite onDuelVoid(String why) {
        RoamingGuardData guard = duellingGuard;
        EnemySprite mage = duellingMage;
        clearDuel();
        if (guard == null)
            return mage;
        guard.inDuel = false;
        String town = mage != null && mage.territoryTarget != null ? mage.territoryTarget.getDisplayName() : "your town";
        System.out.println("[TFR-RoamGuard] no contest at " + town + " (" + why + ") - "
                + RoamingGuards.displayName(guard.tier) + " walks home unhurt, the town defends itself");
        sendHome(guard);
        return mage;
    }

    /**
     * Round 173 (code review G6, user: "should lose the fight"). Called at the end of
     * WorldStage.load(): a guard still flagged {@code inDuel} was saved in the middle of its fight -
     * the game was closed or crashed during a watched duel, or saved inside a simulation window - and
     * that fight will never report a result. It counts as the guard's loss, scored the way
     * onDuelFinished(false) scores one: the guard is out of commission, the defeat goes on the record
     * like any guard fight (round 166), and the mage WorldStage.save() wrote back at the gate walks
     * into the town on the next frame, which then defends itself. Before this round the save simply
     * lost the mage, and the reloaded guard walked home unhurt from an attack that no longer existed.
     *
     * @param enemies WorldStage's freshly loaded enemy list - read only, to find the mage at the gate
     */
    public static void resolveInterruptedDuels(List<Pair<Float, EnemySprite>> enemies) {
        int day = WorldSave.getCurrentSave().getWorld().getCurrentDay();
        for (RoamingGuardData guard : RoamingGuards.roster()) {
            if (!guard.inDuel || guard == duellingGuard)
                continue;
            guard.inDuel = false;
            PointOfInterest town = poiById(guard.missionPoiId);
            // save() appends the fought mage after every live one, so the LAST mage aimed at this town
            // is the one this guard was fighting (an earlier one would be a mage queued at the gate).
            EnemySprite mage = null;
            if (town != null && enemies != null) {
                for (Pair<Float, EnemySprite> pair : enemies) {
                    EnemySprite e = pair.getValue();
                    if (e != null && e.territoryTarget != null && town.getID().equals(e.territoryTarget.getID()))
                        mage = e;
                }
            }
            String townName = town != null ? town.getDisplayName() : "your town";
            System.out.println("[TFR-RoamGuard] " + RoamingGuards.displayName(guard.tier) + "'s fight at " + townName
                    + " never finished (the game closed, or was saved, mid-fight) - counted as a LOSS"
                    + (mage != null ? "; " + mage.getName() + " is at the gate" : "; no attacker was saved with it"));
            if (mage != null && mage.getData() != null && mage.getData().fixedDeck == null)
                forge.adventure.scene.DuelScene.recordStatistics(mage, mage.getName(), false);
            RoamingGuards.onDefeated(guard, day);
            GameHUD.getInstance().addNotification("[RED]Your " + RoamingGuards.displayName(guard.tier)
                    + " guard's fight at " + townName + " never finished - it counts as a loss. Out of commission for "
                    + RoamingGuards.recoveryDays() + " days.", true);
        }
    }

    private static void sendHome(RoamingGuardData guard) {
        guard.missionPoiId = "";
        guard.returningHome = true;
        guard.deployed = true;
    }

    // ------------------------------------------------------------------ helpers

    private static RoamingGuardData guardAssignedTo(String poiId) {
        for (RoamingGuardData guard : RoamingGuards.roster()) {
            if (!guard.returningHome && poiId.equals(guard.missionPoiId))
                return guard;
        }
        return null;
    }

    private static boolean isPlayerOwned(PointOfInterest poi) {
        return TownRestoration.isTownRestored(
                WorldSave.getCurrentSave().peekPointOfInterestChanges(poi.getID()));
    }

    /** The teleport network is hub-and-spoke with the Capitol as hub (round 138), so a guard can
     *  only skip the journey when BOTH ends are wired. */
    private static boolean canTeleportTo(PointOfInterest target) {
        if (!EconomyBuildings.capitolHasTeleporter())
            return false;
        forge.adventure.pointofintrest.PointOfInterestChanges changes =
                WorldSave.getCurrentSave().peekPointOfInterestChanges(target.getID());
        return changes != null && changes.hasEconomyBuildingOfType(EconomyBuildings.TELEPORTER);
    }

    /** Where a deployed guard is walking: the Capitol when it is heading home, else the town it was
     *  sent to. Null for a guard that is not on the road (at rest, downed, or with a stale mission
     *  id) - exactly the guards moveGuards() skips or stands down. Round 162, for the minimap's
     *  guard lines. */
    public static PointOfInterest destination(RoamingGuardData guard, int day) {
        if (!guard.deployed || guard.isOutOfCommission(day))
            return null;
        return guard.isIdle() ? RoamingGuards.capitol() : poiById(guard.missionPoiId);
    }

    private static PointOfInterest poiById(String id) {
        if (id == null || id.isEmpty())
            return null;
        for (PointOfInterest poi : WorldSave.getCurrentSave().getWorld().getAllPointOfInterest()) {
            if (poi.getID().equals(id))
                return poi;
        }
        return null;
    }

    /** Drops every live guard actor - called when the world changes under us (load, New Game+). */
    public static void reset(SpriteGroup foregroundSprites) {
        for (CharacterSprite sprite : sprites.values()) {
            if (foregroundSprites != null)
                foregroundSprites.removeActor(sprite);
        }
        sprites.clear();
        strolls.clear(); // round 233
        clearDuel();
        waitingMage = null;
    }
}
