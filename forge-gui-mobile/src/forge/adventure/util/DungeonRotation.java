package forge.adventure.util;

import forge.adventure.data.AdventureQuestData;
import forge.adventure.data.ConfigData;
import forge.adventure.data.PointOfInterestData;
import forge.adventure.pointofintrest.PointOfInterest;
import forge.adventure.stage.GameHUD;
import forge.adventure.world.World;
import forge.adventure.world.WorldSave;

/**
 * Dungeon rotation (user request 2026-08-08): generic hostile dungeons/caves appear and disappear
 * across the map over time, so the overworld doesn't stay static. Mechanism: hide/show via the
 * (now-honored) persisted {@link PointOfInterest#setActive} flag - hidden POIs stop rendering,
 * can't be entered, drop off the minimap on the next marker refresh, and are excluded from NEW
 * quest target selection (AdventureQuestStage already filters on getActive()). Reappearing in
 * place after a cooldown is the "new dungeon appears" half - true relocation isn't practical
 * (POI positions are baked into the chunk-indexed world registry at world-gen), and a returning
 * dungeon after 10-30 hidden days reads the same to the player.
 * <p>
 * Safety rules (see MOD_CHANGELOG.md for the full POI taxonomy this was derived from):
 * <ul>
 * <li>Only type "dungeon"/"cave" POIs carrying the "Hostile" tag rotate - castles, capitals,
 * towns, Spawn, all sideboss* types (Planeswalker/unique bosses), friendly caves (Oasis etc),
 * and DEBUGZONE are structurally excluded.</li>
 * <li>Anything tagged "Story" or belonging to a quest LINE ("Quest_*" name/tag) never rotates.</li>
 * <li>A dungeon currently targeted by an active STORY quest never despawns (timer just
 * re-rolls); one targeted by an active SIDE quest gets SIDEQUEST_EXTENSION_DAYS added instead of
 * despawning, and 3 loss-attempts before a defeat can despawn it.</li>
 * </ul>
 * Losing a duel inside a rotatable dungeon despawns it immediately (user spec) - unless it's an
 * active side-quest target, in which case the player gets MAX_QUEST_ATTEMPTS tries with an
 * "attempts remaining" warning each loss.
 * <p>
 * Opt-in per-plane via config.json ("dungeonRotationEnabled": true), default off - inert on
 * Shandalar and every other stock plane. All timers/counters persist on World (poiDespawnDay/
 * poiRespawnDay/poiFailedAttempts), keyed by PointOfInterest.getID().
 */
public class DungeonRotation {
    // Pool-based rotation (user redesign 2026-08-08): world-gen places POOL_MULTIPLIER times the
    // normal count of every rotatable dungeon/cave, only 1/POOL_MULTIPLIER start visible, and a
    // despawn activates a RESERVE location instead of the same spot returning later - dungeons
    // genuinely appear somewhere else. World.generateNew()'s placement loop reads this multiplier.
    public static final int POOL_MULTIPLIER = 5;
    // First-guess constants, tune after testing - a visible dungeon lives 20-60 days before
    // vanishing; a just-hidden location can't be re-picked as a fresh spawn for 10-30 days (so a
    // vanished dungeon doesn't pop straight back where it was).
    private static final int DESPAWN_MIN_DAYS = 20;
    private static final int DESPAWN_MAX_DAYS = 60;
    private static final int RESPAWN_MIN_DAYS = 10;
    private static final int RESPAWN_MAX_DAYS = 30;
    // Per user spec, exactly: "+30 days added to the timer" for an active side-quest target,
    // "3 chances" on losses inside one.
    private static final int SIDEQUEST_EXTENSION_DAYS = 30;
    private static final int MAX_QUEST_ATTEMPTS = 3;

    private DungeonRotation() {}

    private static boolean isEnabled() {
        ConfigData configData = Config.instance().getConfigData();
        return configData != null && configData.dungeonRotationEnabled;
    }

    // The despawn-eligibility gate. Deliberately a whitelist shape (must be dungeon/cave AND
    // Hostile) with explicit story exclusions, so anything new/unusual added later defaults to
    // NOT rotating rather than vanishing by surprise. Public data-level variant so World's POI
    // placement loop can apply POOL_MULTIPLIER to exactly the same set.
    public static boolean isRotatableData(PointOfInterestData data) {
        if (data == null)
            return false;
        if (!"dungeon".equalsIgnoreCase(data.type) && !"cave".equalsIgnoreCase(data.type))
            return false;
        if (data.name == null || data.name.startsWith("Quest_") || "DEBUGZONE".equals(data.name) || "Test".equals(data.name))
            return false;
        boolean hostile = false;
        if (data.questTags != null) {
            for (String tag : data.questTags) {
                if (tag == null)
                    continue; // real data has null entries (e.g. MageTowerC6)
                if ("Story".equals(tag) || tag.startsWith("Quest_"))
                    return false;
                if ("Hostile".equals(tag))
                    hostile = true;
            }
        }
        return hostile;
    }

    static boolean isRotatable(PointOfInterest poi) {
        return poi != null && isRotatableData(poi.getData());
    }

    /**
     * Called once from World.generateNew() right after POI placement, BEFORE the minimap/marker
     * bake - the placement loop placed POOL_MULTIPLIER x the normal count of every rotatable
     * dungeon/cave, and this hides all but 1/POOL_MULTIPLIER of them (the rest become the reserve
     * pool despawning dungeons swap into). The visible density therefore matches a non-rotation
     * world exactly. The active target persists on World so the daily tick can keep the visible
     * count level for the whole game.
     */
    public static void initializeNewWorld(World world) {
        if (!isEnabled())
            return;
        java.util.List<PointOfInterest> rotatable = new java.util.ArrayList<>();
        for (PointOfInterest poi : world.getAllPointOfInterest()) {
            if (isRotatable(poi))
                rotatable.add(poi);
        }
        if (rotatable.isEmpty())
            return;
        int activeTarget = Math.max(1, Math.round(rotatable.size() / (float) POOL_MULTIPLIER));
        java.util.Collections.shuffle(rotatable, world.getRandom());
        for (int i = activeTarget; i < rotatable.size(); i++)
            rotatable.get(i).setActive(false); // reserve pool - no cooldown, immediately swappable
        world.setPoiActiveTarget(activeTarget);
        System.out.println("[DungeonRotation] new world: " + activeTarget + " of " + rotatable.size()
                + " rotatable dungeons/caves active, the rest held in reserve");
    }

    private static final int QUEST_NONE = 0, QUEST_SIDE = 1, QUEST_STORY = 2;

    // Whether a live quest in the player's log currently targets this POI instance - the
    // static "Sidequest" tag on POI data only marks quest-pool ELIGIBILITY and is deliberately
    // ignored here; what protects a dungeon is a live quest actually pointing at it.
    // Checks EVERY still-pending stage's target, not just the currently-active stage's
    // (2026-08-16 review finding: initialize() binds all stages' targets at quest generation,
    // so a Clear stage behind an unmet prerequisite already holds a bound dungeon - the old
    // getTargetPOI()-based check ignored it, and that dungeon could despawn before the player
    // ever advanced far enough to need it).
    private static int activeQuestStatus(PointOfInterest poi) {
        int status = QUEST_NONE;
        for (AdventureQuestData quest : Current.player().getQuests()) {
            for (PointOfInterest target : quest.getAllPendingTargetPOIs()) {
                if (target == null || !target.getID().equals(poi.getID()))
                    continue;
                if (quest.storyQuest)
                    return QUEST_STORY; // strongest protection wins
                status = QUEST_SIDE;
            }
        }
        return status;
    }

    /**
     * Called by AdventureQuestStage the moment a quest stage binds this POI as its target
     * (2026-08-16 user request: "confirm the dungeon actually exists... if not, we need to
     * spawn that into existence. Also, when given we need to add 30 days to that location's
     * despawn timer"). Two jobs: (1) if the chosen POI is currently a hidden reserve slot,
     * force-spawn it - setActive(true), clear its cooldown/attempt bookkeeping, refresh the
     * minimap - so a quest never points at a location the player can't see or enter; (2) either
     * way, push its despawn day out by SIDEQUEST_EXTENSION_DAYS so the freshly-given quest has
     * a guaranteed runway regardless of how far through its natural lifetime the dungeon
     * already was. Harmless no-op for non-rotatable targets (towns, story dungeons, bosses) and
     * on planes without rotation. May briefly leave one more dungeon active than
     * poiActiveTarget - activateFromReserve() only ever fills UP to the target, never culls,
     * so the density self-corrects at the next natural despawn.
     */
    public static void onQuestTargetBound(PointOfInterest poi) {
        if (!isEnabled() || !isRotatable(poi))
            return;
        World world = WorldSave.getCurrentSave().getWorld();
        int currentDay = world.getCurrentDay();
        String id = poi.getID();
        if (!poi.getActive()) {
            poi.setActive(true);
            world.getPoiRespawnDay().remove(id);
            world.getPoiFailedAttempts().remove(id);
            world.getPoiDespawnDay().put(id,
                    currentDay + rollDays(world, DESPAWN_MIN_DAYS, DESPAWN_MAX_DAYS) + SIDEQUEST_EXTENSION_DAYS);
            world.refreshWorldMapMarkers();
            System.out.println("[DungeonRotation] " + poi.getDisplayName()
                    + " force-spawned from reserve as a new quest target, despawns day " + world.getPoiDespawnDay().get(id));
        } else {
            Integer despawnDay = world.getPoiDespawnDay().get(id);
            int base = despawnDay != null ? despawnDay
                    : currentDay + rollDays(world, DESPAWN_MIN_DAYS, DESPAWN_MAX_DAYS);
            world.getPoiDespawnDay().put(id, base + SIDEQUEST_EXTENSION_DAYS);
            System.out.println("[DungeonRotation] quest target " + poi.getDisplayName()
                    + " timer extended " + SIDEQUEST_EXTENSION_DAYS + " days, despawns day " + world.getPoiDespawnDay().get(id));
        }
    }

    /** Called from WorldStage's day-change block, alongside the other daily systems. */
    public static void processDaysPassed(int newDayCount) {
        if (!isEnabled())
            return;
        World world = WorldSave.getCurrentSave().getWorld();
        boolean changed = false;
        java.util.List<PointOfInterest> activeRotatable = new java.util.ArrayList<>();
        for (PointOfInterest poi : world.getAllPointOfInterest()) {
            if (isRotatable(poi) && poi.getActive())
                activeRotatable.add(poi);
        }
        // Old save / pre-pool world: lock the target to whatever's currently visible, preserving
        // that world's density (a NEW world's target was set by initializeNewWorld() instead, to
        // 1/POOL_MULTIPLIER of its deliberately-overprovisioned pool).
        if (world.getPoiActiveTarget() <= 0 && !activeRotatable.isEmpty())
            world.setPoiActiveTarget(activeRotatable.size());
        for (PointOfInterest poi : activeRotatable) {
            String id = poi.getID();
            Integer despawnDay = world.getPoiDespawnDay().get(id);
            if (despawnDay == null) {
                // First sight of this POI (fresh world, newly activated, or a save predating the
                // feature) - seed a lifetime rather than despawning anything on day one.
                world.getPoiDespawnDay().put(id, newDayCount + rollDays(world, DESPAWN_MIN_DAYS, DESPAWN_MAX_DAYS));
                continue;
            }
            if (newDayCount < despawnDay)
                continue;
            int questStatus = activeQuestStatus(poi);
            if (questStatus == QUEST_STORY) {
                // Never pull a story quest's target out from under the player - just re-roll.
                world.getPoiDespawnDay().put(id, newDayCount + rollDays(world, DESPAWN_MIN_DAYS, DESPAWN_MAX_DAYS));
            } else if (questStatus == QUEST_SIDE) {
                // Active side quest points here - "30 days should be added to the timer before it
                // disappears" (user spec). Re-extended each time it comes due while the quest is
                // still active, so a long-running quest keeps its target.
                world.getPoiDespawnDay().put(id, despawnDay + SIDEQUEST_EXTENSION_DAYS);
                System.out.println("[DungeonRotation] " + poi.getDisplayName() + " is a side-quest target, extending its timer " + SIDEQUEST_EXTENSION_DAYS + " days");
            } else {
                hidePoi(world, poi, newDayCount, null);
                changed = true;
            }
        }
        changed |= activateFromReserve(world, newDayCount);
        // Batched minimap refresh (2026-08-26 perf, user-approved trade-off): the [TFR-DayTick]
        // instrumentation attributed a steady ~120ms of every late-game day-rollover to this one
        // call - refreshWorldMapMarkers() is a FULL minimap ground rebake + marker redraw + fog
        // pixmap rebuild, and by mid-game the rotation genuinely changes something almost every
        // day. Daily rotation changes now only mark the map dirty, and the heavy refresh runs at
        // most once per MARKER_REFRESH_INTERVAL_DAYS - a despawned dungeon's minimap icon can
        // linger up to that many days stale (it's already non-enterable and gone from the main
        // map immediately; only the baked minimap pixels lag). Player-driven paths stay
        // immediate: quest force-spawns (extendForQuestTarget) and the player personally
        // clearing/defeating a dungeon (onDungeonDefeat/onDungeonClear) still call
        // refreshWorldMapMarkers() directly - those are rare, player-visible moments.
        if (changed)
            markerRefreshDirty = true;
        if (markerRefreshDirty && newDayCount - lastMarkerRefreshDay >= MARKER_REFRESH_INTERVAL_DAYS) {
            world.refreshWorldMapMarkers();
            markerRefreshDirty = false;
            lastMarkerRefreshDay = newDayCount;
        }
    }

    // Session-local batching state for the daily marker refresh above. Static/transient by
    // design: a fresh session's first dirty day refreshes immediately (lastMarkerRefreshDay
    // starts far enough in the "past" that the interval check always passes - NOT Integer.
    // MIN_VALUE, which would underflow the subtraction and never fire), which also covers
    // loading a save whose minimap was left stale by a previous session's pending batch.
    private static final int MARKER_REFRESH_INTERVAL_DAYS = 3;
    private static boolean markerRefreshDirty = false;
    private static int lastMarkerRefreshDay = -1_000_000;

    /**
     * Forget the session-local batching baseline. Called from WorldStage.clearCache() (every Load
     * and every new world) - 2026-09-02 review finding: after a New Game+ from a day-500 run, or an
     * in-game Load of an earlier save, {@code newDayCount - lastMarkerRefreshDay} went negative and
     * the batched minimap refresh was suppressed until the new run caught up with the old day count.
     */
    public static void resetSessionState() {
        markerRefreshDirty = false;
        lastMarkerRefreshDay = -1_000_000;
    }

    // Pool-swap: bring RESERVE locations into play until the visible count is back at the
    // target - a despawned dungeon is thereby replaced by one appearing somewhere ELSE on the
    // map (user redesign 2026-08-08), not by the same spot returning later. A just-hidden
    // location's cooldown (poiRespawnDay) keeps it out of the draw for 10-30 days so despawns
    // don't bounce straight back.
    private static boolean activateFromReserve(World world, int currentDay) {
        int activeCount = 0;
        java.util.List<PointOfInterest> eligibleReserve = new java.util.ArrayList<>();
        for (PointOfInterest poi : world.getAllPointOfInterest()) {
            if (!isRotatable(poi))
                continue;
            if (poi.getActive()) {
                activeCount++;
                continue;
            }
            Integer cooldownUntil = world.getPoiRespawnDay().get(poi.getID());
            if (cooldownUntil == null || currentDay >= cooldownUntil)
                eligibleReserve.add(poi);
        }
        int target = world.getPoiActiveTarget();
        boolean changed = false;
        while (activeCount < target && !eligibleReserve.isEmpty()) {
            PointOfInterest pick = eligibleReserve.remove(world.getRandom().nextInt(eligibleReserve.size()));
            pick.setActive(true);
            world.getPoiRespawnDay().remove(pick.getID());
            world.getPoiFailedAttempts().remove(pick.getID());
            world.getPoiDespawnDay().put(pick.getID(), currentDay + rollDays(world, DESPAWN_MIN_DAYS, DESPAWN_MAX_DAYS));
            System.out.println("[DungeonRotation] " + pick.getDisplayName() + " has appeared on the map");
            activeCount++;
            changed = true;
        }
        return changed;
    }

    /**
     * Called from MapStage.exitDungeon() when the player was DEFEATED inside a dungeon/cave.
     * Non-rotatable POIs (story dungeons, bosses, towns...) are untouched - the plain "kicked
     * out" behavior stays exactly as it was for them.
     */
    public static void onDungeonDefeat(PointOfInterest poi) {
        if (!isEnabled() || !isRotatable(poi))
            return;
        World world = WorldSave.getCurrentSave().getWorld();
        int questStatus = activeQuestStatus(poi);
        if (questStatus == QUEST_STORY)
            return; // story targets never vanish, defeat or not
        int currentDay = world.getCurrentDay();
        if (questStatus == QUEST_SIDE) {
            int attempts = world.getPoiFailedAttempts().getOrDefault(poi.getID(), 0) + 1;
            world.getPoiFailedAttempts().put(poi.getID(), attempts);
            int remaining = MAX_QUEST_ATTEMPTS - attempts;
            if (remaining > 0) {
                // Bold, not color markup - notifications render tint-black, which erases inline
                // colors (see GameHUD.addNotification()'s comment).
                GameHUD.getInstance().addNotification("Defeated at " + poi.getDisplayName() + " - [*]"
                        + remaining + " attempt" + (remaining == 1 ? "" : "s") + " remaining[*] before it is lost!");
                System.out.println("[DungeonRotation] defeat at side-quest target " + poi.getDisplayName() + ", " + remaining + " attempt(s) remaining");
                return;
            }
            hidePoi(world, poi, currentDay, "Your final attempt at " + poi.getDisplayName() + " has failed - it is lost!");
        } else {
            // 2026-08-19 user request: routine, non-quest despawns fire constantly over a normal
            // playthrough - no popup for this case (the "N attempts remaining" side-quest warning
            // above and the final-loss message stay, since those carry real quest-risk info).
            hidePoi(world, poi, currentDay, null);
        }
        activateFromReserve(world, currentDay); // a replacement appears elsewhere - density stays level
        world.refreshWorldMapMarkers();
    }

    /**
     * Called from AdventureQuestController.updateQuestsWin() the moment the player has CLEARED
     * a rotatable dungeon/cave - killed every enemy inside, not merely won one fight in it
     * (2026-08-18 user request: "Does the dungeon also disappear if you 'complete' it... Silly
     * to have an empty dungeon on the map... we should have it de-spawn, to make room for new
     * dungeons"). Unlike onDungeonDefeat(), a clear is unconditional success - there's no
     * attempts/grace system here, it despawns immediately, same as a rotatable dungeon's final
     * loss does. A dungeon currently targeted by a STORY quest is still exempt (identical
     * exemption to onDungeonDefeat() - "story targets never vanish"); a side-quest target
     * despawns on clear too, since its own Clear objective is already satisfied by this same
     * event (see AdventureQuestStage's Clear case) - there's nothing left to come back for.
     * Non-rotatable POIs (story dungeons, bosses, towns...) are untouched, same as
     * onDungeonDefeat().
     */
    public static void onDungeonClear(PointOfInterest poi) {
        if (!isEnabled() || !isRotatable(poi))
            return;
        if (activeQuestStatus(poi) == QUEST_STORY)
            return; // story targets never vanish
        World world = WorldSave.getCurrentSave().getWorld();
        int currentDay = world.getCurrentDay();
        // 2026-08-19 user request: same as onDungeonDefeat()'s routine case - no popup, this fires
        // too often over a normal playthrough to be worth a notification every time.
        hidePoi(world, poi, currentDay, null);
        activateFromReserve(world, currentDay); // a replacement appears elsewhere - density stays level
        world.refreshWorldMapMarkers();
    }

    private static void hidePoi(World world, PointOfInterest poi, int currentDay, String notification) {
        // 2026-08-19 fix (user log review): guard against a double-roll - a combat-triggered
        // despawn (onDungeonDefeat/onDungeonClear) and processDaysPassed()'s own natural-expiry
        // check can both fire for the same POI on the same day (no prior coordination between
        // them), each re-rolling a fresh random respawn day and overwriting the other's. Once
        // inactive, a second call is a no-op - re-rolling an already-despawned POI's cooldown
        // serves no purpose and was silently discarding the first roll every time it happened.
        if (!poi.getActive())
            return;
        poi.setActive(false);
        world.getPoiDespawnDay().remove(poi.getID());
        world.getPoiFailedAttempts().remove(poi.getID());
        world.getPoiRespawnDay().put(poi.getID(), currentDay + rollDays(world, RESPAWN_MIN_DAYS, RESPAWN_MAX_DAYS));
        System.out.println("[DungeonRotation] " + poi.getDisplayName() + " despawned until day " + world.getPoiRespawnDay().get(poi.getID()));
        if (notification != null)
            GameHUD.getInstance().addNotification(notification);
    }

    private static int rollDays(World world, int min, int max) {
        return min + world.getRandom().nextInt(max - min + 1);
    }
}
