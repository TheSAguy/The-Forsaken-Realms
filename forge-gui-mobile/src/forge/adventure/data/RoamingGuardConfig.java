package forge.adventure.data;

/**
 * Backing class for the plane's "config tables/roaming_guards.json" (MOD_SCOPE #116, user spec
 * 2026-09-07). Same dedicated-table pattern as WarChampionData / FrontierSpawnData - hand-editable,
 * no code change needed to retune. Absent file leaves the feature switched off.
 * <p>
 * Four named life fields rather than a map: libGDX's Json reads named primitives reliably, and the
 * tier ladder is fixed by EconomyBuildings.GUARD_TIERS_ASCENDING anyway.
 */
public class RoamingGuardConfig {
    /** How many roaming guards the player may keep at once. 0 disables the feature entirely. */
    public int maxGuards;

    /** Starting life per tier, weakest to strongest. This is duel starting life, so it is the
     *  single biggest lever on how often a guard wins - the mages it fights run 10 to 70. */
    public int lifeApprentice;
    public int lifeAdept;
    public int lifeMaster;
    public int lifeArchmage;

    /**
     * Overworld speed is derived from the PLAYER's base speed rather than hardcoded, per the user's
     * rule: "if the player is at 40, we will have Archmage be 40 and each tier below that -2
     * speed." So Archmage matches the player exactly and each rung down loses this many points.
     * Deriving it means a change to playerBaseSpeed carries the guards with it instead of silently
     * desyncing them.
     */
    public int speedStepBelowPlayer;

    /** In-game days a defeated guard spends out of commission before returning to duty. */
    public int recoveryDays;
}
