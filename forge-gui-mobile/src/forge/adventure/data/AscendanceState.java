package forge.adventure.data;

/**
 * Round 493 - one character's Ascendance (util/Ascendance). Deliberately not Serializable and never written to the save
 * as an object: Ascendance.save/load store its fields under their own keys in the player's data, with containsKey
 * guards (the round-90 rule: no new serializable class enters the save).
 */
public class AscendanceState {
    /** True for a character started by New Game or New Game+ while the plane has Ascendance on. A save from before it
     *  loads with false and plays exactly as it always has: no Power, no main-slot limit (the user's decision). */
    public boolean on;
    /** Every point of Power this run - the level is read from it (Ascendance.levelFor). */
    public int power;
    /** Round 494: the levels whose pick-1-of-3 reward is still to be taken, oldest first - a one-time reward scales with
     *  the level it was earned at, not the level it is taken at. */
    public final java.util.ArrayList<Integer> pendingLevels = new java.util.ArrayList<>();
    /** Round 494: the offer on the table for the oldest pending level, so closing the dialog does not re-roll it. */
    public final java.util.ArrayList<String> offer = new java.util.ArrayList<>();
    /** Round 494: lasting rewards taken, id -> picks. */
    public final java.util.LinkedHashMap<String, Integer> picks = new java.util.LinkedHashMap<>();
    /** Round 496: the level sheet - "level|what it gave", one per level reached since round 496. */
    public final java.util.ArrayList<String> history = new java.util.ArrayList<>();
    /** Round 494: Morning Vigor - the day it last counted duels, and how many it has covered that day. */
    public int vigorDay = -1;
    public int vigorUsed;
    /** Round 505: a quest's Power held until its dialog is read (the intro's 50 waits for the tutorial-or-skip choice),
     *  and what it is for. Saved, so a save made while the dialog was open pays it on the next load. */
    public int deferredPower;
    public String deferredSource = "";
    /** Round 505: set by load when Power was held - the HUD pays it (no dialog is left to wait for after a load). */
    public boolean deferredLoaded;

    public void reset() {
        on = false;
        power = 0;
        pendingLevels.clear();
        offer.clear();
        picks.clear();
        history.clear();
        vigorDay = -1;
        vigorUsed = 0;
        deferredPower = 0;
        deferredSource = "";
        deferredLoaded = false;
    }
}
