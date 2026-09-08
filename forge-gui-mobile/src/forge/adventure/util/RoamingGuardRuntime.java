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

    /** Set while a guard duel is being fought, so WorldStage.setWinner() knows to route the result
     *  here instead of treating it as one of the player's own fights. */
    private static RoamingGuardData duellingGuard;
    private static EnemySprite duellingMage;

    public static RoamingGuardData duellingGuard() {
        return duellingGuard;
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
        moveGuards(delta, day, foregroundSprites);
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
                    + mage.getData().speed + " vs " + (int) RoamingGuards.speedFor(guard.tier) + ")"
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
        // Drop sprites for guards that are gone, downed, or no longer deployed.
        List<RoamingGuardData> stale = new ArrayList<>();
        for (Map.Entry<RoamingGuardData, CharacterSprite> entry : sprites.entrySet()) {
            RoamingGuardData guard = entry.getKey();
            if (!roster.contains(guard) || !guard.deployed || guard.isOutOfCommission(day))
                stale.add(guard);
        }
        for (RoamingGuardData guard : stale) {
            CharacterSprite sprite = sprites.remove(guard);
            if (sprite != null)
                foregroundSprites.removeActor(sprite);
        }

        for (RoamingGuardData guard : roster) {
            if (!guard.deployed || guard.isOutOfCommission(day))
                continue;
            PointOfInterest destination = guard.isIdle() ? home : poiById(guard.missionPoiId);
            if (destination == null) {
                guard.deployed = false;
                continue;
            }
            Vector2 goal = destination.getPosition();
            float speed = RoamingGuards.speedFor(guard.tier);
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
                }
                // Otherwise it is standing at the threatened town, waiting to intercept.
            } else {
                float step = Math.min(distance, speed * delta);
                guard.x += dx / distance * step;
                guard.y += dy / distance * step;
            }

            CharacterSprite sprite = sprites.get(guard);
            if (sprite == null) {
                sprite = new CharacterSprite(AdventurePlayer.current().spriteName());
                sprites.put(guard, sprite);
                foregroundSprites.addActor(sprite);
            }
            sprite.setPosition(guard.x, guard.y);
        }
    }

    // ------------------------------------------------------------------ interception

    /**
     * Called from WorldStage the instant a mage reaches its target town, BEFORE
     * TerritoryControl.onMageArrived(). Returns true when a guard was standing there and has taken
     * the fight - the caller must then stop processing that mage, because the duel decides whether
     * it ever reaches the town at all.
     */
    public static boolean interceptOnArrival(EnemySprite mage) {
        if (!RoamingGuards.isEnabled() || mage.territoryTarget == null || duellingGuard != null)
            return false;
        RoamingGuardData guard = guardAssignedTo(mage.territoryTarget.getID());
        if (guard == null || guard.returningHome || guard.deckCards.length == 0)
            return false;
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
            return false;
        }
        duellingGuard = guard;
        duellingMage = mage;
        System.out.println("[TFR-RoamGuard] " + RoamingGuards.displayName(guard.tier) + " intercepts a "
                + mage.getData().tier + " mage at " + mage.territoryTarget.getDisplayName()
                + " (guard life " + guard.maxLife + ", deck \"" + guard.deckName + "\", "
                + (guard.watchMatches ? "watched" : "simulated") + ")");
        return true;
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
        int day = WorldSave.getCurrentSave().getWorld().getCurrentDay();
        String town = mage != null && mage.territoryTarget != null
                ? mage.territoryTarget.getDisplayName() : "your town";
        if (guardWon) {
            System.out.println("[TFR-RoamGuard] " + RoamingGuards.displayName(guard.tier)
                    + " WON at " + town + " - the attack is broken");
            GameHUD.getInstance().addNotification("[GREEN]Your " + RoamingGuards.displayName(guard.tier)
                    + " guard broke the attack on " + town + "!", true);
            sendHome(guard);
            return null;
        }
        RoamingGuards.onDefeated(guard, day);
        GameHUD.getInstance().addNotification("[RED]Your " + RoamingGuards.displayName(guard.tier)
                + " guard fell at " + town + " and is out of commission for "
                + RoamingGuards.recoveryDays() + " days.", true);
        return mage; // the mage carries on to the town, which now defends itself
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
        clearDuel();
    }
}
