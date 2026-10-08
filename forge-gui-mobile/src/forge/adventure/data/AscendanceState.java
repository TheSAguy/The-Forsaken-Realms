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
    /** Level-ups whose pick-1-of-3 reward is still to be taken. */
    public int pendingChoices;

    public void reset() {
        on = false;
        power = 0;
        pendingChoices = 0;
    }
}
