package forge.adventure.util;

public enum AdventureQuestEventType {
    ENTERPOI,
    REPUTATION,
    MAPFLAG,
    QUESTFLAG,
    CHARACTERFLAG,
    LEAVEPOI,
    MATCHCOMPLETE,
    QUESTCOMPLETE,
    DESPAWN,
    ARENACOMPLETE,
    ARENAMATCHCOMPLETE,
    EVENTCOMPLETE,
    EVENTMATCHCOMPLETE,
    RECEIVEITEM,
    USEITEM,
    /** Round 240: a rotatable dungeon or cave despawned because the player cleared it - see
     *  DungeonRotation.onDungeonClear() and the ClearDungeons objective. */
    DUNGEONCLEARED

}
