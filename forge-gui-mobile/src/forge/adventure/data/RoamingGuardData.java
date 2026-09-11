package forge.adventure.data;

/**
 * One roaming guard (MOD_SCOPE #116, user spec 2026-09-07). A champion hired at the Capitol who
 * carries one of the player's own decks, walks the overworld, and races an attacking mage to a
 * threatened town.
 * <p>
 * <b>Deliberately not Serializable and never written to the save as an object.</b> It is persisted
 * field by field into a nested {@code SaveFileData} (see RoamingGuards.save/load), the same idiom
 * the player's own decks already use - this project has a standing rule against adding new
 * serializable classes to the save after the round-90 save-wipe, and a plain holder read through
 * explicit keys cannot move the save format however this class changes.
 */
public class RoamingGuardData {
    /** Same four-rung ladder as hired local guards: Common/Uncommon/Rare/Mythic, displayed as
     *  Apprentice/Adept/Master/Archmage. Sets starting life AND overworld speed. */
    public String tier = "Common";
    public int maxLife = 12;

    /** The deck this guard carries, and the EXACT card list taken from the player's collection.
     *  The list is the source of truth for giving those cards back - never recompute it from the
     *  deck, which can be edited while the guard holds it. */
    public String deckName = "";
    public String[] deckCards = new String[0];

    /** Which enemy ranks this guard is allowed to engage, in the tier ladder's own ascending order
     *  (Apprentice, Adept, Master, Archmage). An unchecked rank is avoided, not fought. */
    public boolean[] engageTier = {true, true, true, true};

    /** Which enemy COLORS this guard is allowed to engage, indexed by TerritoryControl.COLORS
     *  (white, blue, black, red, green - the same WUBRG order the standings and reputation screens
     *  already use). Round 148, user spec: "I think we need to re-work the Mage Attack orders, I
     *  want to add Color as an option". A guard refuses a mage whose territory colour is unchecked
     *  exactly as it refuses an unchecked rank - both filters must pass. */
    public boolean[] engageColor = {true, true, true, true, true};

    /** Whether the player watches this guard's duels or only sees the result. Every guard match is
     *  really played by the AI either way - this is presentation, not resolution. */
    public boolean watchMatches = true;

    public int hiredDay;
    public int lastPaidDay;

    /** 0 while the guard is alive and available. Otherwise the in-game day it returns to duty
     *  after being defeated. Dismissing during this window forfeits the deck (user spec). */
    public int downUntilDay;

    /** POI id of the town this guard is currently racing an attacker to, or empty when idle.
     *  A guard handles one threat at a time and returns to the Capitol before taking another. */
    public String missionPoiId = "";
    /** True while the guard is on its way back to the Capitol after resolving (or missing) a
     *  threat - it cannot accept a new mission until it gets home. */
    public boolean returningHome;

    /** Overworld position. Only meaningful while deployed; a guard at rest sits at the Capitol. */
    public float x;
    public float y;
    public boolean deployed;

    /** Round 173 (code review G6): true from the moment this guard takes a fight until its result is
     *  in. Persisted, so a save taken while the fight runs - the watched fight's own autosave, any
     *  autosave inside a simulation window, a manual save - knows the fight never finished, and the
     *  load scores it as the guard's LOSS (user ruling) instead of forgetting the attack. */
    public boolean inDuel;

    /** Round 163 (MOD_SCOPE #118): what this guard wears, one item per slot, moved here from the
     *  Armory storage and back through ArmoryStorage's verbs only. Whole ItemData objects, the same
     *  way the player's inventory holds them, so a worn item keeps its identity (longID) and its
     *  state (cracked) across the move and back. Saved as an ItemData[] inside this guard's own
     *  sub-data - ItemData is already serialized whole into every save, so no new class enters it. */
    public java.util.ArrayList<ItemData> equipment = new java.util.ArrayList<>();

    public boolean isOutOfCommission(int currentDay) {
        return downUntilDay > 0 && currentDay < downUntilDay;
    }

    public boolean isIdle() {
        return missionPoiId == null || missionPoiId.isEmpty();
    }
}
