package forge.adventure.data;

/**
 * Round 493 - Ascendance, the player's level (design: docs/design/2026-10-08-xp-leveling.md, section 7). Every number
 * the system uses, read from the plane's "config tables/ascendance.json" by util/Ascendance. Loaded from json only and
 * never saved, so it can change shape freely. Rank-indexed arrays follow the tier ladder: Common (Apprentice), Uncommon
 * (Adept), Rare (Master), Mythic (Archmage).
 */
public class AscendanceData {
    /** Power needed to go from level i+1 to i+2: entry 0 is 1 -> 2, up to the last milestone level (30). */
    public int[] xpToNext = new int[0];
    /** The user: "in the config, add options to control the speed of leveling and how much life is given at the
     *  milestone levels". Every award is multiplied by this - 2 levels twice as fast, 0.5 half as fast. */
    public float levelingSpeed = 1f;
    /** Max life added at each of lifeLevels. */
    public int milestoneLife = 1;
    /** The user: "Maybe we don't cap it at 30, but make the leveling MUCH slower after 30 and give +1 life for each
     *  level". Past the curve's end each level costs postCapXpToNext, plus postCapXpStep more per level after that, and
     *  gives postCapLife max life (nothing else). */
    public int postCapXpToNext = 2000;
    public int postCapXpStep = 250;
    public int postCapLife = 1;

    // ---- duel wins
    /** Power for a win, by the enemy's rank. */
    public int[] duelBase = {10, 18, 28, 40};
    /** The outgrown rule: above this Ascendance a rank's wins pay outgrownStep less per level, down to outgrownFloor. */
    public int[] outgrownBandTop = {8, 15, 22, 999};
    public float outgrownStep = 0.10f;
    public float outgrownFloor = 0.20f;
    /** Multipliers - the highest that applies, never stacked. */
    public float bossFactor = 3f;
    public float legendFactor = 2.5f;
    public float firstWinFactor = 2f;
    public float townFightFactor = 2f;
    public float treasureGuardianFactor = 2f;
    public float championFactor = 1.5f;
    public float mageFactor = 1.5f;

    // ---- everything else
    public int firstVisitTown = 5;
    public int firstVisitPlace = 3;
    public int clearCave = 8;
    public int clearDungeon = 15;
    public int restoreTown = 30;
    public int captureTown = 40;
    public int raiseCapitol = 100;
    public int sideQuest = 20;
    public int storyQuest = 50;
    /** An invasion repelled, by the toughest troop rank it lost. */
    public int[] invasion = {50, 75, 100, 150};
    public int pillageStopped = 25;
    public int arenaBracket = 40;
    public int innMatchWin = 15;
    public int innChampion = 30;
    public int treasureFound = 75;

    // ---- level rewards
    /** Levels that give +1 max life (with their title, and a main slot through mainSlotLevels). */
    public int[] lifeLevels = {5, 10, 15, 20, 25, 30};
    /** Main-slot items the player may wear: mainSlotsBase, +1 at each of these levels. */
    public int mainSlotsBase = 1;
    public int[] mainSlotLevels = {5, 10, 15, 20};
    /** Titles at titleLevels, in the same order. */
    public int[] titleLevels = {5, 10, 15, 20, 25, 30};
    public String[] titles = {"Unbound", "Reclaimer", "Warden of Ash", "Seal-breaker", "Sovereign", "Ascendant"};
    /** A roaming guard's main-slot items, by its rank. */
    public int[] guardMainSlots = {2, 3, 4, 5};

    // ---- round 494: the pick-1-of-3 rewards
    /** How many rewards a choice offers. */
    public int offerSize = 3;
    /** The pool. A one-time reward's {@code value} scales with the level it was earned at (gold: value x level); a
     *  lasting one's is per pick (Haggler 0.05 = -5% a pick, the user: "the next time it would be -10% and the 3rd
     *  -15%"). What each id does is util/Ascendance's; the numbers are here. */
    public Choice[] choices = new Choice[0];

    public static class Choice {
        public String id = "";
        public boolean lasting;
        /** Lasting only: picks it can take before it stops being offered. */
        public int maxPicks = 1;
        public float value;
        /** Relative chance of being offered. */
        public float weight = 1f;
    }
}
