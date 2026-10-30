package forge.adventure.data;

/**
 * One race's assigned starting expansions (MOD_SCOPE.md #4 extension, user spec 2026-08-12:
 * "assign each race a unique expansion... each race should have 4 assigned"). Loaded from the
 * plane config.json's "raceEditions" array; `race` matches heroes.json's RAW hero name
 * (HeroListData.getRawRaceName()), `editions` are set codes. AdventurePlayer.create() picks
 * Easy=4 / Normal=3 / Hard=2 / Insane=1 of these at random for the new game's starting unlocks.
 */
public class RaceEditionData {
    public String race;
    public String[] editions;
    /** Round 879 (the Doppelganger): no sets of its own - its pool is every other row's sets, and each game rolls its
     *  own four from it (AdventurePlayer.raceSets). {@code editions} is left out. */
    public boolean anySet;
}
