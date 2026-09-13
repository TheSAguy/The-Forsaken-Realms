package forge.adventure.util;

import forge.adventure.world.World;

/**
 * One batched, shared minimap marker refresh for every daily system that dirties the map.
 * <p>
 * {@link World#refreshWorldMapMarkers()} is a FULL minimap ground rebake + marker redraw + fog
 * pixmap rebuild - measured at ~140ms on a developed world - so a system that calls it on every
 * in-game day it changes something makes the day rollover stutter for the whole late game.
 * <p>
 * This has now been found twice by the same method, log instrumentation:
 * <ul>
 *   <li>2026-08-26, {@code [TFR-DayTick]}: DungeonRotation's daily rotation "attributed a steady
 *       ~120ms of every late-game day-rollover to this one call". Batched, user-approved.</li>
 *   <li>2026-09-12, {@code [TFR-TerritoryPerf]}: TerritoryControl's AI-guard-level pass was still
 *       calling it directly. Days where any guard level changed cost <b>139.7ms</b>; days where
 *       none did cost <b>0.0ms</b> - flat whether one town changed level or nine, because the cost
 *       is the single rebake, not anything per-town. Guard levels tick on a rolling
 *       28-day-per-level schedule across many towns, so something changed on 368 of 439 days.</li>
 * </ul>
 * The batching lives in one shared place rather than being copied per system so that the systems
 * COALESCE. Two independent batchers would each keep their own schedule and could both rebake on
 * the same day, paying twice for one picture.
 * <p>
 * The trade-off, unchanged from the 2026-08-26 decision: a marker can lag up to
 * {@link #MARKER_REFRESH_INTERVAL_DAYS} days on the baked minimap. Nothing about the underlying
 * state lags - a rotated-out dungeon is already non-enterable and gone from the main map, and an
 * AI town's guard level is already live for any duel - only the minimap pixels.
 * <p>
 * Player-driven refreshes deliberately stay immediate and do NOT come through here: a quest force
 * spawn, the player clearing or defeating a dungeon, restoring a town, building the Capitol. Those
 * are rare, player-visible moments where a lagging icon would read as a bug.
 */
public final class MapMarkerRefresh {
    private MapMarkerRefresh() {
    }

    public static final int MARKER_REFRESH_INTERVAL_DAYS = 3;

    // Session-local by design: a fresh session's first dirty day refreshes immediately, since the
    // sentinel sits far enough "in the past" that the interval check always passes. NOT
    // Integer.MIN_VALUE - that underflows the subtraction below and would never fire. This also
    // covers loading a save whose minimap was left stale by a previous session's pending batch.
    private static boolean dirty = false;
    private static int lastRefreshDay = -1_000_000;

    /** Ask for a refresh soon, instead of paying for a full rebake right now. */
    public static void markDirty() {
        dirty = true;
    }

    /**
     * Run at most one refresh per {@link #MARKER_REFRESH_INTERVAL_DAYS}. Call once per day
     * rollover, AFTER every subsystem that might have marked the map dirty.
     */
    public static void flush(World world, int currentDay) {
        if (world == null || !dirty || currentDay - lastRefreshDay < MARKER_REFRESH_INTERVAL_DAYS)
            return;
        world.refreshWorldMapMarkers();
        dirty = false;
        lastRefreshDay = currentDay;
    }

    /**
     * Forget the batching baseline. Called from WorldStage.clearCache() - every Load and every new
     * world. 2026-09-02 review finding, and it applies to this shared version identically: after a
     * New Game+ from a day-500 run, or an in-game Load of an earlier save,
     * {@code currentDay - lastRefreshDay} goes negative and the refresh is suppressed until the new
     * run catches up with the old day count.
     */
    public static void resetSessionState() {
        dirty = false;
        lastRefreshDay = -1_000_000;
    }
}
